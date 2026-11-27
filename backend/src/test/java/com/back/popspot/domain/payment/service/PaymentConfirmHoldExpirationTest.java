package com.back.popspot.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentConfirmRequest;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.repository.PaymentRefundRepository;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.popupStore.dto.PopupStoreCreateRequest;
import com.back.popspot.domain.popupStore.dto.ReservationSlotCreateRequest;
import com.back.popspot.domain.popupStore.entity.PopupFeeType;
import com.back.popspot.domain.popupStore.entity.PopupStore;
import com.back.popspot.domain.popupStore.entity.ReservationSlot;
import com.back.popspot.domain.popupStore.repository.PopupStoreRepository;
import com.back.popspot.domain.popupStore.repository.ReservationSlotRepository;
import com.back.popspot.domain.reservation.entity.Reservation;
import com.back.popspot.domain.reservation.entity.ReservationStatus;
import com.back.popspot.domain.reservation.repository.ReservationRepository;
import com.back.popspot.domain.reservation.scheduler.ReservationExpirationScheduler;
import com.back.popspot.domain.reservation.service.ReservationExpirationService;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 결제 승인과 예약 만료가 각자 트랜잭션을 커밋해야 하므로 테스트 트랜잭션을 쓰지 않는다.
@DisplayName("결제 승인 중 예약 점유 만료 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConfirmHoldExpirationTest extends ContainerIntegrationTestSupport {
	private static final long AMOUNT = 1000L;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private PaymentRefundRepository paymentRefundRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PopupStoreRepository popupStoreRepository;

	@Autowired
	private ReservationSlotRepository reservationSlotRepository;

	@Autowired
	private ReservationRepository reservationRepository;

	@Autowired
	private ReservationExpirationService reservationExpirationService;

	@Autowired
	private TossPaymentsClient tossPaymentsClient;

	// 만료 시점을 테스트가 정하도록 실제 스케줄러는 막고 만료 서비스를 직접 호출한다.
	@MockitoBean
	private ReservationExpirationScheduler reservationExpirationScheduler;

	private User organizer;
	private User user;
	private PopupStore popupStore;
	private ReservationSlot slot;
	private Reservation reservation;
	private Payment payment;

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
	@DisplayName("토스 승인 중에 원래 점유 시각이 지나 만료 작업이 돌아도 예약은 만료되지 않고 결제가 확정된다")
	void holdDoesNotExpireWhileConfirming() {
		// given: 점유가 2초 남은 상태에서 승인을 시작한다.
		LocalDateTime originalHeldUntil = LocalDateTime.now().plusSeconds(2);
		createReservationPayment(originalHeldUntil);
		PaymentConfirmRequest request = confirmRequest();

		// 토스 응답이 원래 점유 시각을 넘겨 돌아오고, 그 사이 예약 만료 작업이 실행된다.
		when(tossPaymentsClient.confirm(any())).thenAnswer(invocation -> {
			sleepUntilAfter(originalHeldUntil);
			reservationExpirationService.expireExpiredReservations();
			return approvedResponse(request);
		});

		// when
		paymentService.confirm(request);

		// then: 결제는 확정되고 보상 취소(환불)는 일어나지 않는다.
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.PAID);
		assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.CONFIRMED);
		verify(tossPaymentsClient, never()).cancel(any());
	}

	@Test
	@DisplayName("승인을 시작할 때 이미 점유가 만료됐으면 토스를 호출하지 않고 거부한다")
	void expiredHoldIsRejectedBeforeCallingToss() {
		// given
		createReservationPayment(LocalDateTime.now().minusSeconds(1));
		PaymentConfirmRequest request = confirmRequest();

		// when & then
		assertThatThrownBy(() -> paymentService.confirm(request))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.RESERVATION_PAYMENT_EXPIRED);
		verify(tossPaymentsClient, never()).confirm(any());
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.READY);
	}

	@Test
	@DisplayName("점유가 승인 보장 시간보다 넉넉히 남아 있으면 점유 만료 시각을 바꾸지 않는다")
	void longHoldIsNotShortened() {
		// given
		LocalDateTime originalHeldUntil = LocalDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.SECONDS);
		createReservationPayment(originalHeldUntil);
		PaymentConfirmRequest request = confirmRequest();
		when(tossPaymentsClient.confirm(any())).thenAnswer(invocation -> approvedResponse(request));

		// when
		paymentService.confirm(request);

		// then
		assertThat(reservationRepository.findById(reservation.getId()).orElseThrow().getHeldUntil())
			.isEqualTo(originalHeldUntil);
	}

	private void createReservationPayment(LocalDateTime heldUntil) {
		String suffix = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now();
		organizer = userRepository.save(User.create("organizer-" + suffix + "@example.test", "주최자"));
		user = userRepository.save(User.create("hold-" + suffix + "@example.test", "점유 만료"));
		popupStore = popupStoreRepository.save(PopupStore.of(organizer, new PopupStoreCreateRequest(
			"점유 만료 테스트", "서울", PopupFeeType.PAID, (int)AMOUNT,
			now.minusDays(1), now.plusDays(10), now.plusDays(1), now.plusDays(20), null, null)));
		slot = reservationSlotRepository.save(ReservationSlot.of(popupStore,
			new ReservationSlotCreateRequest(LocalDate.now().plusDays(3), LocalTime.of(10, 0), 10)));
		reservation = reservationRepository.save(Reservation.createHeld(user, slot, now, heldUntil));
		payment = paymentRepository.saveAndFlush(Payment.createReadyReservationPayment(
			user, reservation, "order-" + suffix, "점유 만료 테스트", AMOUNT, "ready-" + suffix));
	}

	private PaymentConfirmRequest confirmRequest() {
		String suffix = UUID.randomUUID().toString();
		return new PaymentConfirmRequest("pg-" + suffix, payment.getOrderId(), AMOUNT, "confirm-" + suffix);
	}

	private static void sleepUntilAfter(LocalDateTime time) throws InterruptedException {
		while (!LocalDateTime.now().isAfter(time.plus(200, ChronoUnit.MILLIS))) {
			Thread.sleep(100);
		}
	}

	private JsonNode approvedResponse(PaymentConfirmRequest request) {
		return JsonMapper.builder().build().readTree("""
			{
			  "status": "DONE",
			  "paymentKey": "%s",
			  "orderId": "%s",
			  "totalAmount": %d,
			  "approvedAt": "2026-11-25T10:00:00+09:00"
			}
			""".formatted(request.paymentKey(), request.orderId(), request.amount()));
	}
}
