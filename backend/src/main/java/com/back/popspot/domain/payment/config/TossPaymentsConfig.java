package com.back.popspot.domain.payment.config;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// API 주소와 인증 설정
@Configuration
public class TossPaymentsConfig {
	private static final String BASE_URL = "https://api.tosspayments.com";

	// 토스페이먼츠 요청용 RestClient를 생성
	@Bean
	public RestClient tossPaymentsRestClient(TossPaymentsProperties properties) {
		return createRestClient(BASE_URL, properties);
	}

	// 타임아웃이 없으면 토스가 응답하지 않을 때 호출 스레드(스케줄러 포함)가 무기한 대기한다.
	static RestClient createRestClient(String baseUrl, TossPaymentsProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(requestFactory)
			.defaultHeaders(headers ->
				headers.setBasicAuth(properties.secretKey(), "")
			)
			.build();
	}
}
