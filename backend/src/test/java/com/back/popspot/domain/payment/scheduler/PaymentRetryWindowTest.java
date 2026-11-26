package com.back.popspot.domain.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentRefund;
import com.back.popspot.domain.payment.entity.PaymentRefundStatus;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.entity.PaymentType;
import com.back.popspot.domain.payment.repository.PaymentRefundRepository;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.payment.service.PaymentService;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 재시도 기간 조건이 실제 MySQL 쿼리에서 적용되는지 확인한다.
// created_at은 JPA auditing이 채우므로 네이티브 UPDATE로 과거 시각을 만든다.
@DisplayName("결제 환불 재시도 기간 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentRetryWindowTest extends ContainerIntegrationTestSupport {
	private static final long AMOUNT = 1000L;
	private static final long EXPIRED_HOURS = 25L;
	private static final String REASON = "재시도 기간 테스트";

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

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// 실제 스케줄러가 테스트 도중 같은 데이터를 처리하지 않도록 막는다.
	@MockitoBean
	private PaymentCompensationScheduler paymentCompensationScheduler;

	private final List<PaymentRefund> refunds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		refunds.forEach(refund -> {
			paymentRefundRepository.deleteById(refund.getId());
			paymentRepository.deleteById(refund.getPayment().getId());
			userRepository.deleteById(refund.getUser().getId());
		});
		refunds.clear();
	}

	@Test
	@DisplayName("일반 취소 재시도는 생성된 지 24시간이 지난 실패 환불을 다시 시도하지 않는다")
	void retryFailedCancelsSkipsExpiredRefunds() {
		PaymentRefund expired = saveFailedRefund(PaymentStatus.CANCEL_FAILED, "cancel-");
		PaymentRefund recent = saveFailedRefund(PaymentStatus.CANCEL_FAILED, "cancel-");
		ageRefund(expired);

		paymentService.retryFailedCancels();

		assertRetriedOnlyRecent(expired, recent, PaymentStatus.CANCEL_FAILED);
	}

	@Test
	@DisplayName("보상 취소 재시도는 생성된 지 24시간이 지난 실패 환불을 다시 시도하지 않는다")
	void retryFailedCompensationsSkipsExpiredRefunds() {
		PaymentRefund expired = saveFailedRefund(PaymentStatus.COMPENSATION_FAILED, "confirm-compensation:");
		PaymentRefund recent = saveFailedRefund(PaymentStatus.COMPENSATION_FAILED, "confirm-compensation:");
		ageRefund(expired);

		paymentService.retryFailedCompensations();

		assertRetriedOnlyRecent(expired, recent, PaymentStatus.COMPENSATION_FAILED);
	}

	private void assertRetriedOnlyRecent(PaymentRefund expired, PaymentRefund recent, PaymentStatus failedStatus) {
		verify(tossPaymentsClient).cancel(argThat(command ->
			command.idempotencyKey().equals(recent.getIdempotencyKey())));
		verify(tossPaymentsClient, never()).cancel(argThat(command ->
			command.idempotencyKey().equals(expired.getIdempotencyKey())));

		assertThat(statusOf(recent)).isEqualTo(PaymentRefundStatus.DONE);
		assertThat(paymentStatusOf(recent)).isEqualTo(PaymentStatus.CANCELED);
		// 기간이 지난 건은 실패 상태 그대로 남아 수동 처리 대상이 된다.
		assertThat(statusOf(expired)).isEqualTo(PaymentRefundStatus.FAILED);
		assertThat(paymentStatusOf(expired)).isEqualTo(failedStatus);
	}

	private PaymentRefund saveFailedRefund(PaymentStatus failedStatus, String idempotencyKeyPrefix) {
		String suffix = UUID.randomUUID().toString();
		User user = userRepository.save(User.create("retry-" + suffix + "@example.test", "재시도"));
		Payment ready = Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "재시도 테스트", AMOUNT, "ready-" + suffix);
		ready.complete("pg-" + suffix, LocalDateTime.now());
		if (failedStatus == PaymentStatus.COMPENSATION_FAILED) {
			ready.beginCompensation("pg-" + suffix);
			ready.failCompensation();
		} else {
			ready.beginCancel();
			ready.failCancel();
		}
		Payment payment = paymentRepository.saveAndFlush(ready);

		PaymentRefund refund = PaymentRefund.request(payment, user, AMOUNT, REASON, idempotencyKeyPrefix + suffix);
		refund.fail();
		PaymentRefund saved = paymentRefundRepository.saveAndFlush(refund);
		refunds.add(saved);

		// when()으로 다시 스텁하면 앞선 스텁이 null 인자로 실행되므로 doAnswer를 쓴다.
		doAnswer(invocation -> canceledResponse(invocation.getArgument(0)))
			.when(tossPaymentsClient).cancel(any());
		return saved;
	}

	private void ageRefund(PaymentRefund refund) {
		jdbcTemplate.update("update payment_refund set created_at = ? where id = ?",
			LocalDateTime.now().minusHours(EXPIRED_HOURS), refund.getId());
	}

	private PaymentRefundStatus statusOf(PaymentRefund refund) {
		return paymentRefundRepository.findById(refund.getId()).orElseThrow().getStatus();
	}

	private PaymentStatus paymentStatusOf(PaymentRefund refund) {
		return paymentRepository.findById(refund.getPayment().getId()).orElseThrow().getStatus();
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
			    "canceledAt": "2026-11-26T11:00:00+09:00"
			  }]
			}
			""".formatted(command.paymentKey(), command.orderId(), command.amount(), command.idempotencyKey()));
	}
}
