package com.likelion.commute_study_api.ai.support;

import java.net.SocketTimeoutException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.likelion.commute_study_api.ai.AiCallException;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

/**
 * AI 서버(FastAPI) 호출 공통 처리: X-Internal-Key 헤더, 호출별 타임아웃, 에러 변환.
 * 자료 원문과 내부 키는 로그에 남기지 않는다(AI 서버 API 명세 1장).
 */
@Component
public class AiHttpSupport {

	private static final Logger log = LoggerFactory.getLogger(AiHttpSupport.class);

	private final AiProperties aiProperties;

	public AiHttpSupport(AiProperties aiProperties) {
		this.aiProperties = aiProperties;
	}

	public int timeoutSeconds(String key) {
		return aiProperties.getTimeout().getOrDefault(key, 15);
	}

	public <T> T post(String featureId, String path, Object requestBody, Class<T> responseType, int timeoutSeconds) {
		RestClient client = buildClient(timeoutSeconds);
		long start = System.currentTimeMillis();
		try {
			T result = client.post().uri(path).body(requestBody).retrieve().body(responseType);
			log.info("[{}] AI 호출 성공 elapsedMs={}", featureId, System.currentTimeMillis() - start);
			return result;
		} catch (RestClientResponseException e) {
			long elapsedMs = System.currentTimeMillis() - start;
			throw translate(featureId, elapsedMs, e.getStatusCode(), e.getResponseBodyAs(AiErrorBody.class));
		} catch (ResourceAccessException e) {
			long elapsedMs = System.currentTimeMillis() - start;
			if (e.getCause() instanceof SocketTimeoutException) {
				log.error("[{}] AI 서버 응답 지연 elapsedMs={}", featureId, elapsedMs);
				throw new ApiException(AiErrorCode.AI_TIMEOUT);
			}
			log.error("[{}] AI 서버 연결 실패 elapsedMs={} msg={}", featureId, elapsedMs, e.getMessage());
			throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
		}
	}

	public boolean health() {
		RestClient client = buildClient(timeoutSeconds("health"));
		try {
			client.get().uri("/health").retrieve().toBodilessEntity();
			return true;
		} catch (Exception e) {
			log.warn("AI 서버 헬스체크 실패: {}", e.getMessage());
			return false;
		}
	}

	RestClient buildClient(int timeoutSeconds) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		int millis = (int) Duration.ofSeconds(timeoutSeconds).toMillis();
		factory.setConnectTimeout(millis);
		factory.setReadTimeout(millis);
		return RestClient.builder()
				.baseUrl(aiProperties.getServerUrl())
				.requestFactory(factory)
				.defaultHeader("X-Internal-Key", aiProperties.getInternalKey())
				.build();
	}

	private RuntimeException translate(String featureId, long elapsedMs, HttpStatusCode status, AiErrorBody body) {
		String aiCode = body != null && body.error() != null ? body.error().code() : null;
		String message = body != null && body.error() != null ? body.error().message() : null;

		log.error("[{}] AI 서버 에러 status={} code={} elapsedMs={}", featureId, status, aiCode, elapsedMs);

		return switch (aiCode) {
			case "UNSUPPORTED_FILE", "PAGE_LIMIT_EXCEEDED", "TEXT_EXTRACTION_FAILED", "FILE_FETCH_FAILED" ->
					new AiCallException(aiCode, message);
			case "UNAUTHORIZED" -> new ApiException(AiErrorCode.AI_UNAVAILABLE, message);
			case "INVALID_REQUEST" -> new ApiException(CommonErrorCode.INTERNAL_ERROR, "AI 요청 형식이 잘못됐습니다.");
			case "LLM_TIMEOUT" -> new ApiException(AiErrorCode.AI_TIMEOUT, message);
			case "LLM_FAILED" -> new ApiException(AiErrorCode.AI_UNAVAILABLE, message);
			case null, default -> new ApiException(AiErrorCode.AI_UNAVAILABLE, message);
		};
	}
}
