package com.back.popspot.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.dto.PaymentCancelRequest;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentRefund;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.entity.PaymentType;
import com.back.popspot.domain.payment.repository.PaymentRefundRepository;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 각 요청 스레드가 자기 트랜잭션을 실제로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않는다.
@DisplayName("결제 취소 동시성 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentCancelConcurrencyTest extends ContainerIntegrationTestSupport {
	private static final long AMOUNT = 1000L;
	private static final long TIMEOUT_SECONDS = 20L;
	private static final String CANCEL_REASON = "구매자 변심";

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TossPaymentsClient tossPaymentsClient;

	// 두 트랜잭션이 환불 중복 확인을 모두 마친 뒤에 쓰기를 시작하도록 순서를 강제할 때 사용
	@MockitoSpyBean
	private PaymentRefundRepository paymentRefundRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private User user;
	private Payment payment;

	@BeforeEach
	void setUp() {
		String suffix = UUID.randomUUID().toString();
		user = userRepository.save(User.create("cancel-" + suffix + "@example.test", "동시 취소"));
		Payment ready = Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "동시 취소 테스트", AMOUNT, "ready-" + suffix);
		ready.complete("pg-" + suffix, LocalDateTime.now());
		payment = paymentRepository.saveAndFlush(ready);

		when(tossPaymentsClient.cancel(any())).thenAnswer(invocation ->
			canceledResponse(invocation.getArgument(0)));
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
	@DisplayName("두 취소 요청이 모두 '환불 없음'을 읽은 뒤 쓰기를 시작해도 데드락 없이 한 요청만 PG 취소를 호출한다")
	void interleavedCancelsDoNotDeadlock() throws Exception {
		// given: 두 요청 모두 마지막 환불을 조회한 뒤 서로를 기다렸다가 진행한다.
		CyclicBarrier bothRead = new CyclicBarrier(2);
		// 리포지토리가 인터페이스 프록시라 callRealMethod를 쓸 수 없어 같은 조회를 JPQL로 수행한다.
		doAnswer(invocation -> {
			Optional<PaymentRefund> latest = entityManager.createQuery("""
					select r from PaymentRefund r
					where r.payment.id = :paymentId
					order by r.id desc
					""", PaymentRefund.class)
				.setParameter("paymentId", payment.getId())
				.setMaxResults(1)
				.getResultStream()
				.findFirst();
			bothRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return latest;
		}).when(paymentRefundRepository).findFirstByPaymentIdOrderByIdDesc(payment.getId());

		// when
		CancelResult result = cancelConcurrently(2);

		// then
		assertCancelConverged(result, 2);
	}

	@Test
	@DisplayName("서로 다른 멱등키로 취소 요청이 동시에 들어오면 PG 취소 1회, 환불 1건으로 수렴한다")
	void concurrentCancelsConvergeToSingleRefund() throws Exception {
		// when
		CancelResult result = cancelConcurrently(10);

		// then
		assertCancelConverged(result, 10);
	}

	private void assertCancelConverged(CancelResult result, int requestCount) {
		assertThat(result.unexpected()).as("데드락 등 DB 예외 없이 도메인 응답으로 끝나야 한다").isEmpty();
		verify(tossPaymentsClient, times(1)).cancel(any(PaymentCancelCommand.class));
		assertThat(result.succeeded()).isEqualTo(1);
		assertThat(result.rejectedCodes())
			.hasSize(requestCount - 1)
			.containsOnly(ErrorCode.PAYMENT_CANCEL_ALREADY_REQUESTED);
		assertThat(countRefunds()).isEqualTo(1L);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.CANCELED);
	}

	private CancelResult cancelConcurrently(int requestCount) throws Exception {
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		ConcurrentLinkedQueue<ErrorCode> rejectedCodes = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < requestCount; i++) {
				String idempotencyKey = "cancel-" + i + "-" + UUID.randomUUID();
				futures.add(executor.submit(() -> {
					ready.countDown();
					try {
						start.await();
						paymentService.cancel(payment.getId(), user.getId(),
							new PaymentCancelRequest(CANCEL_REASON, idempotencyKey));
						succeeded.incrementAndGet();
					} catch (BusinessException exception) {
						rejectedCodes.add(exception.getErrorCode());
					} catch (Throwable throwable) {
						unexpected.add(throwable);
					}
				}));
			}
			ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			start.countDown();
			for (Future<?> future : futures) {
				future.get(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}
		return new CancelResult(succeeded.get(), List.copyOf(rejectedCodes), List.copyOf(unexpected));
	}

	private long countRefunds() {
		return entityManager.createQuery(
				"select count(r) from PaymentRefund r where r.payment.id = :paymentId", Long.class)
			.setParameter("paymentId", payment.getId())
			.getSingleResult();
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

	private record CancelResult(int succeeded, List<ErrorCode> rejectedCodes, List<Throwable> unexpected) {
	}
}
