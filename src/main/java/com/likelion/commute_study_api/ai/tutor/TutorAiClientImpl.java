package com.likelion.commute_study_api.ai.tutor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.support.AiErrorBody;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

@Component
public class TutorAiClientImpl implements TutorAiClient {

	private static final Logger log = LoggerFactory.getLogger(TutorAiClientImpl.class);

	// 이 프로젝트에는 ObjectMapper가 별도 스프링 빈으로 노출돼 있지 않아 직접 하나 둔다.
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AiProperties aiProperties;

	public TutorAiClientImpl(AiProperties aiProperties) {
		this.aiProperties = aiProperties;
	}

	@Override
	public void ask(TutorAskRequest request, TutorEventListener listener) {
		int timeoutSeconds = aiProperties.getTimeout().getOrDefault("ai05", 15);
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(timeoutSeconds))
				.build();

		String body = writeJson(request);

		HttpRequest httpRequest = HttpRequest.newBuilder()
				.uri(URI.create(aiProperties.getServerUrl() + "/v1/tutor/ask"))
				.header("X-Internal-Key", aiProperties.getInternalKey())
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();

		long start = System.currentTimeMillis();
		HttpResponse<Stream<String>> response;
		try {
			response = client.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
		} catch (IOException e) {
			log.error("[AI-05] 연결 실패: {}", e.getMessage());
			throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
		}

		if (response.statusCode() != 200) {
			// 스트림이 시작되기 전의 오류(400/401)는 SSE가 아니라 평범한 JSON 에러로 온다.
			String errorBody = response.body().collect(Collectors.joining("\n"));
			throw translateStartupError(response.statusCode(), errorBody);
		}

		parseEvents(response.body(), listener, start);
	}

	private void parseEvents(Stream<String> lines, TutorEventListener listener, long start) {
		StringBuilder eventType = new StringBuilder();
		StringBuilder dataBuffer = new StringBuilder();

		lines.forEach(line -> {
			if (line.isEmpty()) {
				dispatch(eventType.toString(), dataBuffer.toString(), listener, start);
				eventType.setLength(0);
				dataBuffer.setLength(0);
			} else if (line.startsWith("event:")) {
				eventType.append(line.substring("event:".length()).trim());
			} else if (line.startsWith("data:")) {
				dataBuffer.append(line.substring("data:".length()).trim());
			}
		});
	}

	private void dispatch(String eventType, String data, TutorEventListener listener, long start) {
		if (eventType.isEmpty()) {
			return;
		}
		try {
			switch (eventType) {
				case "delta" -> listener.onDelta(objectMapper.readValue(data, TutorDeltaEvent.class).text());
				case "done" -> {
					log.info("[AI-05] 완료 elapsedMs={}", System.currentTimeMillis() - start);
					listener.onDone(objectMapper.readValue(data, TutorDoneEvent.class).meta());
				}
				case "error" -> {
					AiErrorBody.AiError error = objectMapper.readValue(data, AiErrorBody.class).error();
					String code = error != null ? error.code() : null;
					String message = error != null ? error.message() : null;
					log.error("[AI-05] 스트림 중 에러 code={}", code);
					listener.onError(code, message);
				}
				default -> log.warn("[AI-05] 알 수 없는 이벤트 type={}", eventType);
			}
		} catch (Exception e) {
			log.error("[AI-05] 이벤트 파싱 실패 type={} msg={}", eventType, e.getMessage());
		}
	}

	private ApiException translateStartupError(int statusCode, String rawBody) {
		String code = null;
		String message = null;
		try {
			AiErrorBody.AiError error = objectMapper.readValue(rawBody, AiErrorBody.class).error();
			if (error != null) {
				code = error.code();
				message = error.message();
			}
		} catch (Exception ignored) {
			// 본문이 기대한 형태가 아니어도 상태 코드만으로 처리한다.
		}
		log.error("[AI-05] 시작 전 오류 status={} code={}", statusCode, code);

		if ("INVALID_REQUEST".equals(code)) {
			return new ApiException(CommonErrorCode.INTERNAL_ERROR, "AI 질문 요청 형식이 잘못됐습니다.");
		}
		return new ApiException(AiErrorCode.AI_UNAVAILABLE, message);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new ApiException(CommonErrorCode.INTERNAL_ERROR, "AI 질문 요청을 만들지 못했습니다.");
		}
	}
}
