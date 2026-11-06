package com.likelion.commute_study_api.ai.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import com.likelion.commute_study_api.ai.AiCallException;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.judge.JudgeResponse;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

/**
 * 실제 네트워크 대신 로컬 HttpServer(JDK 내장)로 AI 서버를 흉내 낸다.
 * RestClient 호출별 타임아웃까지 실제로 검증하기 위해 MockRestServiceServer 대신 이 방식을 쓴다.
 */
class AiHttpSupportTest {

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private AiHttpSupport support(int port) {
		AiProperties props = new AiProperties();
		props.setServerUrl("http://localhost:" + port);
		props.setInternalKey("test-key");
		return new AiHttpSupport(props);
	}

	private HttpServer startServer(int status, String body) throws IOException {
		HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		s.createContext("/", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		s.start();
		return s;
	}

	@Test
	void 성공_응답을_DTO로_받는다() throws IOException {
		server = startServer(200, "{\"correct\":true,\"reason\":\"ok\"}");
		AiHttpSupport support = support(server.getAddress().getPort());

		JudgeResponse response = support.post("AI-03", "/v1/answers/judge-short", Map.of(), JudgeResponse.class, 5);

		assertTrue(response.correct());
		assertEquals("ok", response.reason());
	}

	@Test
	void LLM_FAILED는_AI_UNAVAILABLE로_변환된다() throws IOException {
		server = startServer(502, "{\"error\":{\"code\":\"LLM_FAILED\",\"message\":\"모델 오류\"}}");
		AiHttpSupport support = support(server.getAddress().getPort());

		ApiException e = assertThrows(ApiException.class,
				() -> support.post("AI-02", "/v1/questions/generate", Map.of(), Object.class, 5));

		assertEquals(AiErrorCode.AI_UNAVAILABLE, e.getErrorCode());
	}

	@Test
	void LLM_TIMEOUT은_AI_TIMEOUT으로_변환된다() throws IOException {
		server = startServer(504, "{\"error\":{\"code\":\"LLM_TIMEOUT\",\"message\":\"시간 초과\"}}");
		AiHttpSupport support = support(server.getAddress().getPort());

		ApiException e = assertThrows(ApiException.class,
				() -> support.post("AI-02", "/v1/questions/generate", Map.of(), Object.class, 5));

		assertEquals(AiErrorCode.AI_TIMEOUT, e.getErrorCode());
	}

	@Test
	void UNSUPPORTED_FILE는_원본_코드를_담아_AiCallException으로_던진다() throws IOException {
		server = startServer(422, "{\"error\":{\"code\":\"UNSUPPORTED_FILE\",\"message\":\"지원하지 않는 파일\"}}");
		AiHttpSupport support = support(server.getAddress().getPort());

		AiCallException e = assertThrows(AiCallException.class,
				() -> support.post("AI-01", "/v1/materials/process", Map.of(), Object.class, 5));

		assertEquals("UNSUPPORTED_FILE", e.getAiCode());
		assertEquals("지원하지 않는 파일", e.getMessage());
	}

	@Test
	void INVALID_REQUEST는_백엔드_버그로_보고_INTERNAL_ERROR로_변환한다() throws IOException {
		server = startServer(400, "{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"필드 오류\"}}");
		AiHttpSupport support = support(server.getAddress().getPort());

		ApiException e = assertThrows(ApiException.class,
				() -> support.post("AI-03", "/v1/answers/judge-short", Map.of(), Object.class, 5));

		assertEquals(CommonErrorCode.INTERNAL_ERROR, e.getErrorCode());
	}

	@Test
	void 응답이_오지_않으면_소켓_타임아웃으로_AI_TIMEOUT이_된다() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			try {
				Thread.sleep(3000); // 클라이언트 타임아웃(1초)보다 길게 묵힌다
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(200, -1);
		});
		server.start();
		AiHttpSupport support = support(server.getAddress().getPort());

		ApiException e = assertThrows(ApiException.class,
				() -> support.post("AI-03", "/v1/answers/judge-short", Map.of(), Object.class, 1));

		assertEquals(AiErrorCode.AI_TIMEOUT, e.getErrorCode());
	}
}
