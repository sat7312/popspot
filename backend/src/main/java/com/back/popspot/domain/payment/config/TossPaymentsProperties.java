package com.back.popspot.domain.payment.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 환경변수의 키와 토스 API 호출 타임아웃을 읽기
@ConfigurationProperties(prefix = "toss-payments")
public record TossPaymentsProperties(
	String clientKey,
	String secretKey,
	Duration connectTimeout,
	Duration readTimeout
) {
	// 연결 단계는 결제 처리와 무관하므로 짧게 둔다.
	static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	// 토스 문서는 결제 관련 API의 Read Timeout을 60초로 안내한다.
	// 승인 복구 기준(90초)보다 짧아야 원래 요청이 끝난 뒤에 복구가 시작된다.
	static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

	public TossPaymentsProperties {
		if (connectTimeout == null) {
			connectTimeout = DEFAULT_CONNECT_TIMEOUT;
		}
		if (readTimeout == null) {
			readTimeout = DEFAULT_READ_TIMEOUT;
		}
	}
}
