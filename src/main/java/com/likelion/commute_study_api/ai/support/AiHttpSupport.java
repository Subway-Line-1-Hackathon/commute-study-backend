package com.likelion.commute_study_api.ai.support;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.likelion.commute_study_api.ai.AiCallException;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.exception.ErrorCode;

/**
 * AI 서버(FastAPI) 호출 공통 처리: X-Internal-Key 헤더, 호출별 타임아웃, 에러 변환.
 * 자료 원문과 내부 키는 로그에 남기지 않는다(AI 서버 API 명세 1장).
 */
@Component
public class AiHttpSupport {

	private static final Logger log = LoggerFactory.getLogger(AiHttpSupport.class);

	/** AI 서버 mock이 실패를 흉내 내는 헤더(AI 서버 ↔ 백엔드 API 명세 1장). */
	public static final String MOCK_ERROR_HEADER = "X-Mock-Error";
	private static final Pattern MOCK_ERROR_VALUE = Pattern.compile("[A-Z_]{1,40}");

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
			RestClient.RequestBodySpec request = client.post().uri(path);
			String mockError = mockErrorToForward();
			if (mockError != null) {
				request = request.header(MOCK_ERROR_HEADER, mockError);
				log.warn("[{}] 개발용 X-Mock-Error 전달 code={}", featureId, mockError);
			}
			T result = request.body(requestBody).retrieve().body(responseType);
			log.info("[{}] AI 호출 성공 elapsedMs={}", featureId, System.currentTimeMillis() - start);
			return result;
		} catch (RestClientResponseException e) {
			long elapsedMs = System.currentTimeMillis() - start;
			throw translate(featureId, elapsedMs, e.getStatusCode(), e.getResponseBodyAs(AiErrorBody.class));
		} catch (RestClientException e) {
			// 응답(상태 코드)을 못 받은 경우. 연결 실패와 읽기 타임아웃이 둘 다 이 경로로 온다(RestClient 버전에 따라
			// ResourceAccessException일 수도, 좀 더 일반적인 RestClientException일 수도 있어 원인 체인을 끝까지 본다).
			long elapsedMs = System.currentTimeMillis() - start;
			if (containsSocketTimeout(e)) {
				log.error("[{}] AI 서버 응답 지연 elapsedMs={}", featureId, elapsedMs);
				throw new ApiException(AiErrorCode.AI_TIMEOUT);
			}
			log.error("[{}] AI 서버 연결 실패 elapsedMs={} msg={}", featureId, elapsedMs, e.getMessage());
			throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
		}
	}

	/**
	 * ai.mock-error-passthrough가 켜져 있고 지금 처리 중인 사용자 요청에 X-Mock-Error가 있으면 그 값을 돌려준다.
	 * 요청 스레드 밖(aiExecutor 비동기 호출, 스케줄러)에서는 요청이 없으므로 넘기지 않는다.
	 * 값은 AI 에러 코드 형식(대문자·밑줄)만 받아 다른 헤더 값이 섞여 들어가지 않게 한다.
	 */
	String mockErrorToForward() {
		if (!aiProperties.isMockErrorPassthrough()) {
			return null;
		}
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
			return null;
		}
		String value = attributes.getRequest().getHeader(MOCK_ERROR_HEADER);
		if (value == null || !MOCK_ERROR_VALUE.matcher(value).matches()) {
			return null;
		}
		return value;
	}

	private boolean containsSocketTimeout(Throwable e) {
		Throwable cur = e;
		while (cur != null) {
			if (cur instanceof SocketTimeoutException) {
				return true;
			}
			cur = cur.getCause();
		}
		return false;
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
			case "UNAUTHORIZED" -> apiException(AiErrorCode.AI_UNAVAILABLE, message);
			case "INVALID_REQUEST" -> new ApiException(CommonErrorCode.INTERNAL_ERROR, "AI 요청 형식이 잘못됐습니다.");
			case "LLM_TIMEOUT" -> apiException(AiErrorCode.AI_TIMEOUT, message);
			case "LLM_FAILED" -> apiException(AiErrorCode.AI_UNAVAILABLE, message);
			case null, default -> apiException(AiErrorCode.AI_UNAVAILABLE, message);
		};
	}

	/**
	 * AI 서버가 명세 형식이 아닌 에러 바디(예: FastAPI 기본 401 {"detail": ...})를 주면 message가 null이다.
	 * 그대로 넘기면 로그와 응답에 "null"이 찍히므로 에러 코드의 기본 메시지로 채운다.
	 */
	private static ApiException apiException(ErrorCode errorCode, String message) {
		return new ApiException(errorCode, message != null ? message : errorCode.getMessage());
	}
}
