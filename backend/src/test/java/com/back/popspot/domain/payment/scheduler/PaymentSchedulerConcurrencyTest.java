package com.back.popspot.domain.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.dto.PaymentCancelRequest;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentRefund;
import com.back.popspot.domain.payment.entity.PaymentRefundStatus;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.entity.PaymentType;
import com.back.popspot.domain.payment.repository.PaymentRefundRepository;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.payment.service.PaymentService;
import com.back.popspot.domain.payment.service.PaymentTransactionService;
import com.back.popspot.domain.popupStore.dto.PopupStoreCreateRequest;
import com.back.popspot.domain.popupStore.dto.ReservationSlotCreateRequest;
import com.back.popspot.domain.popupStore.entity.PopupFeeType;
import com.back.popspot.domain.popupStore.entity.PopupStore;
import com.back.popspot.domain.popupStore.entity.ReservationSlot;
import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.domain.popupStore.repository.ReservationSlotRepository;
import com.back.popspot.domain.reservation.entity.Reservation;
import com.back.popspot.domain.reservation.repository.ReservationRepository;
import com.back.popspot.domain.reservation.service.ReservationWaitlistService;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Blue-Green 전환 중 두 인스턴스가 같은 결제 스케줄러 작업을 동시에 실행하는 상황을
// 두 스레드로 재현한다. 각 스레드가 자기 트랜잭션을 커밋해야 하므로 테스트 트랜잭션을 쓰지 않는다.
@DisplayName("결제 스케줄러 다중 인스턴스 동시 실행 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentSchedulerConcurrencyTest extends ContainerIntegrationTestSupport {
	private static final long AMOUNT = 1000L;
	private static final long TIMEOUT_SECONDS = 20L;
	private static final long LOCK_HOLD_MILLIS = 1500L;
	private static final long HOOK_WAIT_SECONDS = 3L;
	private static final String REASON = "결제 승인 후 주문 확정 실패";

	// 스레드가 어느 인스턴스 역할인지 표시해 리포지토리 스텁이 역할별로 순서를 조정한다.
	private static final ThreadLocal<String> INSTANCE = new ThreadLocal<>();

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentTransactionService paymentTransactionService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TossPaymentsClient tossPaymentsClient;

	@MockitoSpyBean
	private PaymentRepository paymentRepository;

	@MockitoSpyBean
	private PaymentRefundRepository paymentRefundRepository;

	@MockitoSpyBean
	private ReservationWaitlistService reservationWaitlistService;

	@Autowired
	private PopupStoreRepository popupStoreRepository;

	@Autowired
	private ReservationSlotRepository reservationSlotRepository;

	@Autowired
	private ReservationRepository reservationRepository;

	// 실제 스케줄러가 테스트 도중 같은 데이터를 처리하지 않도록 막는다.
	@MockitoBean
	private PaymentCompensationScheduler paymentCompensationScheduler;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@PersistenceContext
	private EntityManager entityManager;

	private User user;
	private Payment payment;
	private User organizer;
	private PopupStore popupStore;
	private ReservationSlot slot;
	private Reservation reservation;

	@AfterEach
	void tearDown() {
		if (payment != null) {
			paymentRefundRepository.findAll().stream()
				.filter(refund -> refund.getPayment().getId().equals(payment.getId()))
				.forEach(paymentRefundRepository::delete);
			paymentRepository.deleteById(payment.getId());
		}
		if (reservation != null) {
			reservationRepository.deleteById(reservation.getId());
		}
		if (slot != null) {
			reservationSlotRepository.deleteById(slot.getId());
		}
		if (popupStore != null) {
			popupStoreRepository.deleteById(popupStore.getId());
		}
		if (user != null) {
			userRepository.deleteById(user.getId());
		}
		if (organizer != null) {
			userRepository.deleteById(organizer.getId());
		}
	}

	@Test
	@DisplayName("보상 취소 재시도가 두 인스턴스에서 동시에 돌면 FOR UPDATE로 직렬화되어 PG 취소는 한 번만 호출된다")
	void retryFailedCompensationsIsSerializedByRowLock() throws Exception {
		saveFailedRefund(PaymentStatus.COMPENSATION_FAILED, "confirm-compensation:");

		RetryResult result = runRetryOnTwoInstances(paymentService::retryFailedCompensations);

		assertRetrySerialized(result, PaymentStatus.CANCELED);
	}

	@Test
	@DisplayName("일반 취소 재시도가 두 인스턴스에서 동시에 돌면 FOR UPDATE로 직렬화되어 PG 취소는 한 번만 호출된다")
	void retryFailedCancelsIsSerializedByRowLock() throws Exception {
		saveFailedRefund(PaymentStatus.CANCEL_FAILED, "cancel-");

		RetryResult result = runRetryOnTwoInstances(paymentService::retryFailedCancels);

		assertRetrySerialized(result, PaymentStatus.CANCELED);
	}

	@Test
	@DisplayName("두 인스턴스가 모두 토스 ABORTED를 확인해도 CONFIRMING 결제는 READY로 한 번만 되돌아간다")
	void recoverAbortedConfirmationOnTwoInstances() throws Exception {
		saveStaleConfirmingPayment();
		CyclicBarrier bothQueried = new CyclicBarrier(2);
		when(tossPaymentsClient.findByOrderId(anyString())).thenAnswer(invocation -> {
			bothQueried.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return tossStatus("ABORTED");
		});

		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> paymentService.recoverStaleConfirmations());
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> paymentService.recoverStaleConfirmations());
		CompletableFuture.allOf(first, second).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		Payment recovered = paymentRepository.findById(payment.getId()).orElseThrow();
		assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.READY);
		assertThat(recovered.getPaymentKey()).isNull();
	}

	@Test
	@DisplayName("두 인스턴스가 같은 CONFIRMING 결제를 DONE으로 복구하는 사이 사용자 취소가 커밋돼도 CANCELING이 PAID로 덮어써지지 않는다")
	void recoverDoneConfirmationDoesNotOverwriteCancel() throws Exception {
		// given: 두 인스턴스 모두 복구 대상(CONFIRMING)을 조회하고 토스에서 DONE을 받는다.
		saveStaleConfirmingReservationPayment();
		CyclicBarrier bothQueried = new CyclicBarrier(2);
		CountDownLatch instanceAInHook = new CountDownLatch(1);
		CountDownLatch instanceBInHook = new CountDownLatch(1);
		CountDownLatch cancelCommitted = new CountDownLatch(1);
		when(tossPaymentsClient.findByOrderId(anyString())).thenAnswer(invocation -> {
			bothQueried.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if ("B".equals(INSTANCE.get())) {
				// B는 A가 결제를 읽고 아직 커밋하지 않은 시점에 complete()를 시작한다.
				instanceAInHook.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			return tossDoneResponse(payment.getPaymentKey(), payment.getOrderId());
		});

		// complete()가 결제를 읽은 뒤 커밋하기 전에 호출하는 대기열 삭제 지점에서 순서를 맞춘다.
		// 결제를 어떤 쿼리로 읽는지와 무관하게 "읽기 → (다른 트랜잭션 커밋) → 쓰기" 순서를 만든다.
		// - A: B가 같은 지점에 올 때까지 기다린 뒤 PAID를 커밋한다.
		// - B: 사용자 취소가 커밋될 때까지 기다린 뒤 커밋한다.
		// 잠금 없이 읽으면 B는 스냅샷의 CONFIRMING으로 이 지점에 와서 CANCELING을 PAID로 덮어쓴다.
		// 행 락으로 읽으면 B는 A의 락에 막혀 이 지점에 오지 못하므로 두 대기는 시간 제한으로 풀린다.
		doAnswer(invocation -> {
			String instance = INSTANCE.get();
			if ("A".equals(instance)) {
				instanceAInHook.countDown();
				instanceBInHook.await(HOOK_WAIT_SECONDS, TimeUnit.SECONDS);
			}
			if ("B".equals(instance)) {
				instanceBInHook.countDown();
				cancelCommitted.await(HOOK_WAIT_SECONDS, TimeUnit.SECONDS);
			}
			return invocation.callRealMethod();
		}).when(reservationWaitlistService)
			.deleteByConfirmedReservation(any(User.class), any(ReservationSlot.class));

		// when: A의 완료가 커밋된 직후 사용자가 취소를 시작한다.
		CompletableFuture<Void> instanceA = runAs("A", () -> paymentService.recoverStaleConfirmations());
		CompletableFuture<Void> instanceB = runAs("B", () -> paymentService.recoverStaleConfirmations());
		CompletableFuture<Void> userCancel = instanceA.thenRunAsync(() -> {
			paymentTransactionService.prepareCancel(payment.getId(), user.getId(),
				new PaymentCancelRequest("구매자 변심", "cancel-" + UUID.randomUUID()));
			cancelCommitted.countDown();
		});
		CompletableFuture.allOf(instanceA, instanceB, userCancel).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		// then
		assertThat(instanceAInHook.getCount()).as("A가 결제를 완료 처리해야 한다").isZero();
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
			.as("늦게 커밋한 복구가 사용자 취소(CANCELING)를 PAID로 덮어쓰면 안 된다")
			.isEqualTo(PaymentStatus.CANCELING);
		assertThat(countRefunds(PaymentRefundStatus.REQUESTED)).isEqualTo(1L);
		verify(tossPaymentsClient, never()).cancel(any());
	}

	private RetryResult runRetryOnTwoInstances(Runnable retry) throws Exception {
		CountDownLatch instanceALocked = new CountDownLatch(1);
		ConcurrentHashMap<String, Integer> selectedCounts = new ConcurrentHashMap<>();
		ConcurrentHashMap<String, Long> elapsedMillis = new ConcurrentHashMap<>();

		doAnswer(invocation -> {
			long startedAt = System.nanoTime();
			List<PaymentRefund> locked = findFailedRefundsForUpdate(
				invocation.getArgument(0), invocation.getArgument(1));
			String instance = INSTANCE.get();
			if (instance != null) {
				selectedCounts.put(instance, locked.size());
				elapsedMillis.put(instance, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
			}
			if ("A".equals(instance)) {
				// 행 락을 쥔 채로 B가 같은 조회를 시작할 시간을 준다.
				instanceALocked.countDown();
				Thread.sleep(LOCK_HOLD_MILLIS);
			}
			return locked;
		}).when(paymentRefundRepository)
			.findTop20ByStatusAndCreatedAtAfterOrderByIdAsc(any(PaymentRefundStatus.class), any(LocalDateTime.class));

		CompletableFuture<Void> instanceA = runAs("A", retry);
		instanceALocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		CompletableFuture<Void> instanceB = runAs("B", retry);
		CompletableFuture.allOf(instanceA, instanceB).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		return new RetryResult(selectedCounts, elapsedMillis);
	}

	private void assertRetrySerialized(RetryResult result, PaymentStatus expectedStatus) {
		verify(tossPaymentsClient, times(1)).cancel(any(PaymentCancelCommand.class));
		assertThat(result.selectedCounts()).containsEntry("A", 1).containsEntry("B", 0);
		assertThat(result.elapsedMillis().get("B"))
			.as("B는 A가 커밋할 때까지 행 락을 기다려야 한다")
			.isGreaterThanOrEqualTo(LOCK_HOLD_MILLIS - 200);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(expectedStatus);
		assertThat(countRefunds(PaymentRefundStatus.DONE)).isEqualTo(1L);
	}

	private CompletableFuture<Void> runAs(String instance, Runnable task) {
		return CompletableFuture.runAsync(() -> {
			INSTANCE.set(instance);
			try {
				task.run();
			} finally {
				INSTANCE.remove();
			}
		});
	}

	private void saveFailedRefund(PaymentStatus failedStatus, String idempotencyKeyPrefix) {
		String suffix = UUID.randomUUID().toString();
		user = userRepository.save(User.create("scheduler-" + suffix + "@example.test", "스케줄러"));
		Payment ready = Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "스케줄러 테스트", AMOUNT, "ready-" + suffix);
		ready.complete("pg-" + suffix, LocalDateTime.now());
		if (failedStatus == PaymentStatus.COMPENSATION_FAILED) {
			ready.beginCompensation("pg-" + suffix);
			ready.failCompensation();
		} else {
			ready.beginCancel();
			ready.failCancel();
		}
		payment = paymentRepository.saveAndFlush(ready);

		PaymentRefund refund = PaymentRefund.request(payment, user, AMOUNT, REASON, idempotencyKeyPrefix + suffix);
		refund.fail();
		paymentRefundRepository.saveAndFlush(refund);

		when(tossPaymentsClient.cancel(any())).thenAnswer(invocation ->
			canceledResponse(invocation.getArgument(0)));
	}

	private void saveStaleConfirmingPayment() {
		String suffix = UUID.randomUUID().toString();
		user = userRepository.save(User.create("scheduler-" + suffix + "@example.test", "스케줄러"));
		payment = paymentRepository.saveAndFlush(Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "스케줄러 테스트", AMOUNT, "ready-" + suffix));
		// 승인 호출이 시작된 지 90초가 지나 복구 대상이 된 CONFIRMING 결제
		transactionTemplate.executeWithoutResult(status -> paymentRepository.beginConfirmationIfReady(
			payment.getOrderId(), AMOUNT, "pg-" + suffix, "confirm-" + suffix,
			LocalDateTime.now().minusMinutes(5), PaymentStatus.READY, PaymentStatus.CONFIRMING));
		payment = paymentRepository.findById(payment.getId()).orElseThrow();
	}

	// 점유 중(HELD)인 예약에 연결된 CONFIRMING 결제. 완료 처리 시 대기열 삭제가 호출된다.
	private void saveStaleConfirmingReservationPayment() {
		String suffix = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now();
		organizer = userRepository.save(User.create("organizer-" + suffix + "@example.test", "주최자"));
		user = userRepository.save(User.create("scheduler-" + suffix + "@example.test", "스케줄러"));
		popupStore = popupStoreRepository.save(PopupStore.of(organizer, new PopupStoreCreateRequest(
			"스케줄러 테스트", "서울", PopupFeeType.PAID, (int) AMOUNT,
			now.minusDays(1), now.plusDays(10), now.plusDays(1), now.plusDays(20), null, null)));
		slot = reservationSlotRepository.save(ReservationSlot.of(popupStore,
			new ReservationSlotCreateRequest(LocalDate.now().plusDays(3), LocalTime.of(10, 0), 10)));
		reservation = reservationRepository.save(Reservation.createHeld(user, slot, now, now.plusMinutes(10)));
		payment = paymentRepository.saveAndFlush(Payment.createReadyReservationPayment(
			user, reservation, "order-" + suffix, "스케줄러 테스트", AMOUNT, "ready-" + suffix));
		transactionTemplate.executeWithoutResult(status -> paymentRepository.beginConfirmationIfReady(
			payment.getOrderId(), AMOUNT, "pg-" + suffix, "confirm-" + suffix,
			now.minusMinutes(5), PaymentStatus.READY, PaymentStatus.CONFIRMING));
		payment = paymentRepository.findById(payment.getId()).orElseThrow();
	}

	// 리포지토리가 인터페이스 프록시라 callRealMethod를 쓸 수 없어 같은 조회를 JPQL로 수행한다.
	private List<PaymentRefund> findFailedRefundsForUpdate(PaymentRefundStatus status, LocalDateTime createdAfter) {
		return entityManager.createQuery("""
				select r from PaymentRefund r
				where r.status = :status and r.createdAt > :createdAfter
				order by r.id asc
				""", PaymentRefund.class)
			.setParameter("status", status)
			.setParameter("createdAfter", createdAfter)
			.setLockMode(LockModeType.PESSIMISTIC_WRITE)
			.setMaxResults(20)
			.getResultList();
	}

	private long countRefunds(PaymentRefundStatus status) {
		return entityManager.createQuery("""
				select count(r) from PaymentRefund r
				where r.payment.id = :paymentId and r.status = :status
				""", Long.class)
			.setParameter("paymentId", payment.getId())
			.setParameter("status", status)
			.getSingleResult();
	}

	private JsonNode tossStatus(String status) {
		return JsonMapper.builder().build().readTree("{\"status\": \"%s\"}".formatted(status));
	}

	private JsonNode tossDoneResponse(String paymentKey, String orderId) {
		return JsonMapper.builder().build().readTree("""
			{
			  "status": "DONE",
			  "paymentKey": "%s",
			  "orderId": "%s",
			  "totalAmount": %d,
			  "approvedAt": "2026-11-26T10:00:00+09:00"
			}
			""".formatted(paymentKey, orderId, AMOUNT));
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

	private record RetryResult(
		ConcurrentHashMap<String, Integer> selectedCounts,
		ConcurrentHashMap<String, Long> elapsedMillis
	) {
	}
}
