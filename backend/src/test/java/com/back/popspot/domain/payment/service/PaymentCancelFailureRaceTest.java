package com.back.popspot.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.dto.PaymentCancelRequest;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentRefundStatus;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.entity.PaymentType;
import com.back.popspot.domain.payment.repository.PaymentRefundRepository;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 두 취소 요청이 각자 트랜잭션을 커밋해야 하므로 테스트 트랜잭션을 쓰지 않는다.
@DisplayName("취소 완료 후 늦게 도착한 취소 실패 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCancelFailureRaceTest extends ContainerIntegrationTestSupport {
	private static final long AMOUNT = 1000L;
	private static final long TIMEOUT_SECONDS = 20L;
	private static final String CANCEL_REASON = "구매자 변심";

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentRefundRepository paymentRefundRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TossPaymentsClient tossPaymentsClient;

	private User user;
	private Payment payment;

	@BeforeEach
	void setUp() {
		String suffix = UUID.randomUUID().toString();
		user = userRepository.save(User.create("cancel-race-" + suffix + "@example.test", "취소 경합"));
		Payment ready = Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "취소 경합 테스트", AMOUNT, "ready-" + suffix);
		ready.complete("pg-" + suffix, LocalDateTime.now());
		payment = paymentRepository.saveAndFlush(ready);
	}

	@AfterEach
	void tearDown() {
		paymentRefundRepository.findAll().stream()
			.filter(refund -> refund.getPayment().getId().equals(payment.getId()))
			.forEach(paymentRefundRepository::delete);
		paymentRepository.deleteById(payment.getId());
		userRepository.deleteById(user.getId());
	}

	@Test
	@DisplayName("다른 멱등키 취소가 먼저 끝난 뒤 이전 멱등키 재시도가 실패해도 결제는 취소 완료 상태로 남는다")
	void lateCancelFailureDoesNotRevertCanceledPayment() throws Exception {
		// given: 첫 취소(key1)가 PG 오류로 실패해 결제가 CANCEL_FAILED가 된다.
		String firstKey = "cancel-first-" + UUID.randomUUID();
		String secondKey = "cancel-second-" + UUID.randomUUID();
		CountDownLatch secondInToss = new CountDownLatch(1);
		CountDownLatch firstRetryInToss = new CountDownLatch(1);
		CountDownLatch secondCompleted = new CountDownLatch(1);
		boolean[] firstAttempted = {false};

		when(tossPaymentsClient.cancel(any())).thenAnswer(invocation -> {
			PaymentCancelCommand command = invocation.getArgument(0);
			if (command.idempotencyKey().equals(firstKey)) {
				if (!firstAttempted[0]) {
					firstAttempted[0] = true;
					throw new RestClientException("first attempt failed");
				}
				// key1 재시도: key2가 취소를 끝낸 뒤에 PG가 실패(이미 취소된 결제)를 돌려준다.
				firstRetryInToss.countDown();
				secondCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				throw new RestClientException("already canceled");
			}
			// key2: key1 재시도가 CANCELING으로 다시 진입할 때까지 기다린 뒤 성공한다.
			secondInToss.countDown();
			firstRetryInToss.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return canceledResponse(command);
		});

		assertCancelFailed(firstKey);
		assertThat(statusOf(payment)).isEqualTo(PaymentStatus.CANCEL_FAILED);

		// when: 새 멱등키(key2) 취소가 PG 응답을 기다리는 동안 이전 멱등키(key1)로 재시도한다.
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> second = executor.submit(() -> {
				try {
					cancel(secondKey);
				} finally {
					secondCompleted.countDown();
				}
			});
			assertThat(secondInToss.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			Future<?> firstRetry = executor.submit(() -> assertCancelFailed(firstKey));

			second.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
			firstRetry.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		// then: 실제로 환불된 key2 기준으로 결제는 CANCELED로 남고, 늦은 실패는 해당 환불 기록에만 남는다.
		assertThat(statusOf(payment)).isEqualTo(PaymentStatus.CANCELED);
		assertThat(paymentRefundRepository.findByIdempotencyKey(secondKey).orElseThrow().getStatus())
			.isEqualTo(PaymentRefundStatus.DONE);
		assertThat(paymentRefundRepository.findByIdempotencyKey(firstKey).orElseThrow().getStatus())
			.isEqualTo(PaymentRefundStatus.FAILED);
	}

	private void cancel(String idempotencyKey) {
		paymentService.cancel(payment.getId(), user.getId(), new PaymentCancelRequest(CANCEL_REASON, idempotencyKey));
	}

	private void assertCancelFailed(String idempotencyKey) {
		assertThatThrownBy(() -> cancel(idempotencyKey))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.PAYMENT_CANCEL_FAILED);
	}

	private PaymentStatus statusOf(Payment target) {
		return paymentRepository.findById(target.getId()).orElseThrow().getStatus();
	}

	private JsonNode canceledResponse(PaymentCancelCommand command) {
		return JsonMapper.builder().build().readTree("""
			{
			  "status": "CANCELED",
			  "paymentKey": "%s",
			  "orderId": "%s",
			  "balanceAmount": 0,
			  "cancels": [{
			    "cancelStatus": "DONE",
			    "cancelAmount": %d,
			    "transactionKey": "tx-%s",
			    "canceledAt": "2026-11-25T11:00:00+09:00"
			  }]
			}
			""".formatted(command.paymentKey(), command.orderId(), command.amount(), command.idempotencyKey()));
	}
}
