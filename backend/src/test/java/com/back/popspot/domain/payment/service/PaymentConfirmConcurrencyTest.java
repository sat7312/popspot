package com.back.popspot.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.back.popspot.domain.payment.client.TossPaymentsClient;
import com.back.popspot.domain.payment.dto.PaymentConfirmRequest;
import com.back.popspot.domain.payment.dto.PaymentConfirmResponse;
import com.back.popspot.domain.payment.entity.Payment;
import com.back.popspot.domain.payment.entity.PaymentStatus;
import com.back.popspot.domain.payment.entity.PaymentType;
import com.back.popspot.domain.payment.repository.PaymentRepository;
import com.back.popspot.domain.user.entity.User;
import com.back.popspot.domain.user.repository.UserRepository;
import com.back.popspot.global.exception.BusinessException;
import com.back.popspot.global.exception.ErrorCode;
import com.back.popspot.support.ContainerIntegrationTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 각 요청 스레드가 자기 트랜잭션을 실제로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않는다.
@DisplayName("결제 승인 동시성 통합 테스트 (MySQL)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConfirmConcurrencyTest extends ContainerIntegrationTestSupport {
	private static final int REQUEST_COUNT = 10;
	private static final long AMOUNT = 1000L;
	private static final long TIMEOUT_SECONDS = 20L;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TossPaymentsClient tossPaymentsClient;

	private User user;
	private Payment payment;

	@AfterEach
	void tearDown() {
		if (payment != null) {
			paymentRepository.deleteById(payment.getId());
		}
		if (user != null) {
			userRepository.deleteById(user.getId());
		}
	}

	@Test
	@DisplayName("같은 주문으로 승인 요청이 동시에 들어오면 토스 승인은 한 번만 호출하고 나머지는 처리 중으로 응답한다")
	void concurrentConfirmCallsTossOnce() throws Exception {
		// given
		String suffix = UUID.randomUUID().toString();
		user = userRepository.save(User.create("confirm-" + suffix + "@example.test", "동시 승인"));
		payment = paymentRepository.saveAndFlush(Payment.createReady(
			user, PaymentType.GOODS, "order-" + suffix, "동시 승인 테스트", AMOUNT, "ready-" + suffix));
		PaymentConfirmRequest request = new PaymentConfirmRequest(
			"pg-" + suffix, payment.getOrderId(), AMOUNT, "confirm-" + suffix);

		// 승인을 선점한 요청은 나머지 요청이 모두 응답할 때까지 토스 응답을 기다린다.
		CountDownLatch rejected = new CountDownLatch(REQUEST_COUNT - 1);
		when(tossPaymentsClient.confirm(any())).thenAnswer(invocation -> {
			rejected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return approvedResponse(request);
		});

		CountDownLatch ready = new CountDownLatch(REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger succeeded = new AtomicInteger();
		ConcurrentLinkedQueue<ErrorCode> rejectedCodes = new ConcurrentLinkedQueue<>();
		ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

		// when
		ExecutorService executor = Executors.newFixedThreadPool(REQUEST_COUNT);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < REQUEST_COUNT; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					try {
						start.await();
						paymentService.confirm(request);
						succeeded.incrementAndGet();
					} catch (BusinessException exception) {
						rejectedCodes.add(exception.getErrorCode());
						rejected.countDown();
					} catch (Throwable throwable) {
						unexpected.add(throwable);
						rejected.countDown();
					}
				}));
			}
			ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			start.countDown();
			for (Future<?> future : futures) {
				future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		// then
		assertThat(unexpected).as("DB 예외 없이 도메인 응답으로 끝나야 한다").isEmpty();
		verify(tossPaymentsClient, times(1)).confirm(any());
		assertThat(succeeded.get()).isEqualTo(1);
		assertThat(rejectedCodes)
			.hasSize(REQUEST_COUNT - 1)
			.containsOnly(ErrorCode.PAYMENT_CONFIRM_IN_PROGRESS);
		assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
			.isEqualTo(PaymentStatus.PAID);

		// 승인 완료 후 같은 요청이 다시 오면 토스를 다시 호출하지 않고 기존 결과를 돌려준다.
		PaymentConfirmResponse retried = paymentService.confirm(request);
		assertThat(retried.paymentKey()).isEqualTo(request.paymentKey());
		verify(tossPaymentsClient, times(1)).confirm(any());
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
