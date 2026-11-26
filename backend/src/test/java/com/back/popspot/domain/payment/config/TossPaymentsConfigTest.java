package com.back.popspot.domain.payment.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

@DisplayName("토스 RestClient 타임아웃 설정")
class TossPaymentsConfigTest {
	private static final long SERVER_DELAY_MILLIS = 2_000L;

	private HttpServer server;
	private String baseUrl;

	@BeforeEach
	void setUp() throws Exception {
		// 요청을 받고 응답하지 않는 토스 서버를 흉내 낸다.
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			try {
				Thread.sleep(SERVER_DELAY_MILLIS);
				byte[] body = "{\"status\":\"DONE\"}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (IOException ignored) {
				// 클라이언트가 타임아웃으로 연결을 끊으면 응답 쓰기가 실패할 수 있다.
			} finally {
				exchange.close();
			}
		});
		server.start();
		baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	@DisplayName("토스가 응답 대기 시간 안에 응답하지 않으면 무기한 기다리지 않고 ResourceAccessException으로 끝난다")
	void readTimeoutStopsWaiting() {
		TossPaymentsProperties properties = new TossPaymentsProperties(
			"client-key", "secret-key", Duration.ofSeconds(1), Duration.ofMillis(300));
		RestClient restClient = TossPaymentsConfig.createRestClient(baseUrl, properties);

		long startedAt = System.nanoTime();
		assertThatThrownBy(() -> restClient.get().uri("/v1/payments/orders/order-1").retrieve().body(String.class))
			.isInstanceOf(ResourceAccessException.class);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

		assertThat(elapsedMillis)
			.as("서버 지연(%dms)을 기다리지 않고 응답 대기 시간 근처에서 끝나야 한다", SERVER_DELAY_MILLIS)
			.isLessThan(1_000L);
	}

	@Test
	@DisplayName("응답 대기 시간 안에 응답하면 정상적으로 본문을 받는다")
	void respondsWithinReadTimeout() {
		TossPaymentsProperties properties = new TossPaymentsProperties(
			"client-key", "secret-key", Duration.ofSeconds(1), Duration.ofSeconds(5));
		RestClient restClient = TossPaymentsConfig.createRestClient(baseUrl, properties);

		String body = restClient.get().uri("/v1/payments/orders/order-1").retrieve().body(String.class);

		assertThat(body).contains("DONE");
	}

	@Test
	@DisplayName("타임아웃을 설정하지 않으면 연결 5초, 응답 대기 60초를 쓴다")
	void defaultTimeouts() {
		TossPaymentsProperties properties = new TossPaymentsProperties("client-key", "secret-key", null, null);

		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(60));
	}
}
