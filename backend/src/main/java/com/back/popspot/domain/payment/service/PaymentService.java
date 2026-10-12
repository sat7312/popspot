package com.back.popspot.domain.payment.service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.dto.PaymentCancelPreparation;
import com.back.popspot.domain.payment.dto.PaymentCancelRequest;
import com.back.popspot.domain.payment.dto.PaymentCancelResponse;
import com.back.popspot.domain.payment.dto.PaymentConfirmPreparation;
import com.back.popspot.domain.payment.dto.PaymentConfirmRequest;
import com.back.popspot.domain.payment.dto.PaymentConfirmResponse;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
	private static final String CONFIRM_COMPENSATION_REASON = "결제 승인 후 주문 확정 실패";
	private static final String TOSS_ALREADY_PROCESSED_CODE = "ALREADY_PROCESSED_PAYMENT";
	// 진행 중인 승인 호출과 겹치지 않도록 충분히 지난 결제만 복구 대상으로 본다.
	private static final long CONFIRM_STALE_SECONDS = 90L;
	private static final Set<String> UNAPPROVED_TOSS_STATUSES = Set.of("ABORTED", "EXPIRED");
	private static final Set<ErrorCode> COMPENSATION_REQUIRED_ERRORS = Set.of(
		ErrorCode.RESERVATION_PAYMENT_EXPIRED,
		ErrorCode.RESERVATION_PAYMENT_NOT_ALLOWED_STATUS,
		ErrorCode.GOODS_ORDER_PAYMENT_EXPIRED,
		ErrorCode.GOODS_ORDER_PAYMENT_NOT_ALLOWED_STATUS
	);

	private final PaymentTransactionService paymentTransactionService;
	private final TossPaymentsClient tossPaymentsClient;

	// 결제 승인 요청을 준비하고 토스 승인 후 완료 처리
	public PaymentConfirmResponse confirm(PaymentConfirmRequest request) {
		PaymentConfirmPreparation preparation = paymentTransactionService.prepare(request);

		if (preparation.isCompleted()) {
			return preparation.existingResponse();
		}

		JsonNode tossResponse;
		try {
			tossResponse = tossPaymentsClient.confirm(request);
		} catch (HttpClientErrorException exception) {
			if (!isDefiniteRejection(exception)) {
				throw exception;
			}
			paymentTransactionService.rejectConfirmation(request);
			throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_REJECTED);
		}
		validateTossConfirmResponse(tossResponse, request);

		return completeConfirmedPayment(request, extractApprovedAt(tossResponse));
	}

	// 승인 결과가 확정되지 않은 결제를 토스 조회 결과로 복구
	public void recoverStaleConfirmations() {
		LocalDateTime startedBefore = LocalDateTime.now().minusSeconds(CONFIRM_STALE_SECONDS);
		paymentTransactionService.findStaleConfirmations(startedBefore).forEach(request -> {
			try {
				recoverConfirmation(request);
			} catch (RuntimeException exception) {
				log.error("Payment confirm recovery failed. orderId={}", request.orderId(), exception);
			}
		});
	}

	// 토스의 실제 결제 상태에 맞춰 승인 진행 중인 결제를 확정하거나 되돌림
	private void recoverConfirmation(PaymentConfirmRequest request) {
		JsonNode tossPayment;
		try {
			tossPayment = tossPaymentsClient.findByOrderId(request.orderId());
		} catch (HttpClientErrorException.NotFound exception) {
			paymentTransactionService.rejectConfirmation(request);
			return;
		}

		String status = tossPayment.path("status").asString(null);
		if ("DONE".equals(status)) {
			validateTossConfirmResponse(tossPayment, request);
			completeConfirmedPayment(request, extractApprovedAt(tossPayment));
			return;
		}

		// 그 외 상태는 승인 여부가 아직 확정되지 않았으므로 다음 주기에 다시 확인
		if (UNAPPROVED_TOSS_STATUSES.contains(status)) {
			paymentTransactionService.rejectConfirmation(request);
		}
	}

	// 토스 승인이 끝난 결제를 완료 처리하고 확정할 수 없으면 보상 취소
	private PaymentConfirmResponse completeConfirmedPayment(PaymentConfirmRequest request, LocalDateTime approvedAt) {
		try {
			return paymentTransactionService.complete(request, approvedAt);
		} catch (BusinessException exception) {
			if (requiresCompensation(exception)) {
				compensateConfirmedPayment(request);
			}
			throw exception;
		}
	}

	// 토스가 승인하지 않았음이 확실한 응답인지 확인
	// 409와 ALREADY_PROCESSED_PAYMENT는 승인됐을 수 있어 복구 스케줄러에 맡긴다.
	private boolean isDefiniteRejection(HttpClientErrorException exception) {
		return exception.getStatusCode().value() != HttpStatus.CONFLICT.value()
			&& !exception.getResponseBodyAsString().contains(TOSS_ALREADY_PROCESSED_CODE);
	}

	// 승인 완료 후 보상 취소가 필요한 예외인지 확인
	private boolean requiresCompensation(BusinessException exception) {
		return COMPENSATION_REQUIRED_ERRORS.contains(exception.getErrorCode());
	}

	// 승인 후 주문 확정 실패 결제를 보상 취소 처리
	private void compensateConfirmedPayment(PaymentConfirmRequest request) {
		PaymentCancelCommand command;
		try {
			command = paymentTransactionService.prepareCompensation(
				request,
				CONFIRM_COMPENSATION_REASON
			);
		} catch (RuntimeException preparationException) {
			log.error("Payment compensation preparation failed. orderId={}",
				request.orderId(), preparationException);
			return;
		}

		executeCompensation(command);
	}

	// 실패한 보상 취소를 재시도
	public void retryFailedCompensations() {
		List<PaymentCancelCommand> commands = paymentTransactionService.prepareFailedCompensations();
		commands.forEach(this::executeCompensation);
	}

	// 실패한 일반 취소를 재시도
	public void retryFailedCancels() {
		List<PaymentCancelCommand> commands = paymentTransactionService.prepareFailedCancels();
		commands.forEach(command -> {
			try {
				executeCancel(command);
			} catch (RuntimeException exception) {
				log.error("Payment cancel retry failed. paymentId={}, orderId={}",
					command.paymentId(), command.orderId(), exception);
			}
		});
	}

	// 토스 취소 API로 보상 취소를 실행
	private void executeCompensation(PaymentCancelCommand command) {
		try {
			JsonNode tossResponse = tossPaymentsClient.cancel(command);
			validateTossCancelResponse(tossResponse, command);
			JsonNode cancel = tossResponse.path("cancels").get(tossResponse.path("cancels").size() - 1);
			paymentTransactionService.completeCompensation(
				command,
				cancel.path("transactionKey").asString(),
				extractCanceledAt(cancel)
			);
		} catch (RuntimeException compensationException) {
			try {
				paymentTransactionService.failCompensation(command);
			} catch (RuntimeException recordingException) {
				compensationException.addSuppressed(recordingException);
			}
			log.error("Payment compensation failed. paymentId={}, orderId={}",
				command.paymentId(), command.orderId(), compensationException);
		}
	}

	// 결제 취소 요청을 준비하고 토스 취소 후 완료 처리
	public PaymentCancelResponse cancel(Long paymentId, Long userId, PaymentCancelRequest request) {
		PaymentCancelPreparation preparation = paymentTransactionService.prepareCancel(paymentId, userId, request);
		if (preparation.isCompleted()) {
			return preparation.existingResponse();
		}

		PaymentCancelCommand command = preparation.command();
		JsonNode tossResponse;
		try {
			tossResponse = tossPaymentsClient.cancel(command);
		} catch (RestClientException exception) {
			paymentTransactionService.failCancel(command.idempotencyKey());
			throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
		}

		try {
			validateTossCancelResponse(tossResponse, command);
		} catch (BusinessException exception) {
			paymentTransactionService.failCancel(command.idempotencyKey());
			throw exception;
		}

		try {
			return completeCancel(command, tossResponse);
		} catch (RuntimeException exception) {
			recordFailedCancel(command, exception);
			throw exception;
		}
	}

	// 토스 취소 API로 일반 취소를 실행
	private PaymentCancelResponse executeCancel(PaymentCancelCommand command) {
		try {
			JsonNode tossResponse = tossPaymentsClient.cancel(command);
			validateTossCancelResponse(tossResponse, command);
			return completeCancel(command, tossResponse);
		} catch (RuntimeException exception) {
			recordFailedCancel(command, exception);
			throw exception;
		}
	}

	// 일반 취소 실패 상태를 기록
	private void recordFailedCancel(PaymentCancelCommand command, RuntimeException exception) {
		try {
			paymentTransactionService.failCancel(command.idempotencyKey());
		} catch (RuntimeException recordingException) {
			exception.addSuppressed(recordingException);
		}
	}

	// 토스 취소 응답으로 결제 취소를 완료 처리
	private PaymentCancelResponse completeCancel(PaymentCancelCommand command, JsonNode tossResponse) {
		JsonNode cancel = tossResponse.path("cancels").get(tossResponse.path("cancels").size() - 1);
		return paymentTransactionService.completeCancel(
			command,
			cancel.path("transactionKey").asString(),
			extractCanceledAt(cancel)
		);
	}

	// 토스 결제 승인 응답이 요청 정보와 일치하는지 검증
	private void validateTossConfirmResponse(JsonNode tossResponse, PaymentConfirmRequest request) {
		if (!"DONE".equals(tossResponse.path("status").asString(null))
			|| !request.paymentKey().equals(tossResponse.path("paymentKey").asString(null))
			|| !request.orderId().equals(tossResponse.path("orderId").asString(null))
			|| !String.valueOf(request.amount()).equals(tossResponse.path("totalAmount").asString(null))
			|| tossResponse.path("approvedAt").asString(null) == null
			|| tossResponse.path("approvedAt").asString(null).isBlank()) {
			throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_RESPONSE_MISMATCH);
		}
	}

	// 토스 승인 시각을 LocalDateTime으로 변환
	private LocalDateTime extractApprovedAt(JsonNode tossResponse) {
		String approvedAt = tossResponse.path("approvedAt").asString(null);
		try {
			return OffsetDateTime.parse(approvedAt).toLocalDateTime();
		} catch (DateTimeParseException e) {
			throw new BusinessException(ErrorCode.PAYMENT_CONFIRM_RESPONSE_MISMATCH);
		}
	}

	// 토스 결제 취소 응답이 요청 정보와 일치하는지 검증
	private void validateTossCancelResponse(JsonNode tossResponse, PaymentCancelCommand command) {
		JsonNode cancels = tossResponse.path("cancels");
		if (!"CANCELED".equals(tossResponse.path("status").asString(null))
			|| !command.paymentKey().equals(tossResponse.path("paymentKey").asString(null))
			|| !command.orderId().equals(tossResponse.path("orderId").asString(null))
			|| tossResponse.path("balanceAmount").asLong(-1L) != 0L
			|| !cancels.isArray()
			|| cancels.isEmpty()) {
			throw new BusinessException(ErrorCode.PAYMENT_CANCEL_RESPONSE_MISMATCH);
		}

		JsonNode cancel = cancels.get(cancels.size() - 1);
		if (!"DONE".equals(cancel.path("cancelStatus").asString(null))
			|| cancel.path("cancelAmount").asLong(-1L) != command.amount()
			|| cancel.path("transactionKey").asString("").isBlank()
			|| cancel.path("canceledAt").asString("").isBlank()) {
			throw new BusinessException(ErrorCode.PAYMENT_CANCEL_RESPONSE_MISMATCH);
		}
	}

	// 토스 취소 시각을 LocalDateTime으로 변환
	private LocalDateTime extractCanceledAt(JsonNode cancel) {
		try {
			return OffsetDateTime.parse(cancel.path("canceledAt").asString()).toLocalDateTime();
		} catch (DateTimeParseException exception) {
			throw new BusinessException(ErrorCode.PAYMENT_CANCEL_RESPONSE_MISMATCH);
		}
	}
}
