package com.back.popspot.domain.payment.client;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.back.popspot.domain.payment.dto.PaymentConfirmRequest;
import com.back.popspot.domain.payment.dto.PaymentCancelCommand;
import com.back.popspot.domain.payment.dto.TossPaymentCancelRequest;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

// 토스 승인 API 요청 실행
@Component
@RequiredArgsConstructor
public class TossPaymentsClient {
	private static final String CONFIRM_IDEMPOTENCY_KEY_PREFIX = "confirm:";

	private final RestClient tossPaymentsRestClient;

	// 토스 결제 승인 API를 호출
	public JsonNode confirm(PaymentConfirmRequest request) {
		return tossPaymentsRestClient.post()
			.uri("/v1/payments/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", confirmIdempotencyKey(request))
			.body(request)
			.retrieve()
			.body(JsonNode.class);
	}

	// 토스 결제 취소 API를 호출
	public JsonNode cancel(PaymentCancelCommand command) {
		return tossPaymentsRestClient.post()
			.uri("/v1/payments/{paymentKey}/cancel", command.paymentKey())
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", command.idempotencyKey())
			.body(new TossPaymentCancelRequest(command.cancelReason()))
			.retrieve()
			.body(JsonNode.class);
	}

	// 주문 번호로 토스 결제 상태를 조회
	public JsonNode findByOrderId(String orderId) {
		return tossPaymentsRestClient.get()
			.uri("/v1/payments/orders/{orderId}", orderId)
			.retrieve()
			.body(JsonNode.class);
	}

	// 거절 후 새 결제 키로 다시 승인할 수 있도록 결제 키까지 포함
	private String confirmIdempotencyKey(PaymentConfirmRequest request) {
		return CONFIRM_IDEMPOTENCY_KEY_PREFIX + request.orderId() + ":" + request.paymentKey();
	}
}
