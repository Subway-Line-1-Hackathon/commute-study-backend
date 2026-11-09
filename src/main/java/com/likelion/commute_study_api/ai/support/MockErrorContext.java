package com.likelion.commute_study_api.ai.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.task.TaskDecorator;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 개발·데모용 X-Mock-Error 값을 AI 호출 지점까지 옮긴다(#36 실패 경로 시험).
 * <ul>
 * <li>요청 스레드에서는 사용자 요청의 헤더를 읽는다</li>
 * <li>비동기 작업(aiExecutor, feedbackExecutor, AI 질문 스레드)은 작업을 맡긴 순간의 값을 {@link #wrap}으로 넘겨받는다.
 * 그래서 업로드 후 단원화(AI-01)·사전 생성(AI-02), 세션 종료 후 피드백(AI-06)에도 붙는다</li>
 * <li>스케줄러처럼 사용자 요청에서 시작하지 않은 작업에는 값이 없다</li>
 * </ul>
 * 값 형식: {@code LLM_TIMEOUT}(모든 AI 호출) 또는 {@code AI-02:LLM_FAILED}(그 기능의 호출에만).
 * 형식이 맞지 않으면 무시한다. 실제로 AI 서버에 보낼지는 ai.mock-error-passthrough 설정을 보는 쪽이 정한다.
 */
public final class MockErrorContext {

	public static final String HEADER = "X-Mock-Error";

	private static final Pattern VALUE = Pattern.compile("(?:(AI-0[1-6]):)?([A-Z_]{1,40})");

	private static final ThreadLocal<String> INHERITED = new ThreadLocal<>();

	private MockErrorContext() {
	}

	/** 지금 스레드에서 유효한 값(형식 검사 통과)을 돌려준다. 없으면 null. */
	static String current() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			String value = attributes.getRequest().getHeader(HEADER);
			return value != null && VALUE.matcher(value).matches() ? value : null;
		}
		return INHERITED.get();
	}

	/**
	 * 이 기능(featureId, 예: AI-02)의 호출에 붙일 에러 코드. 값이 없거나 다른 기능을 지정했으면 null.
	 */
	public static String codeFor(String featureId) {
		String value = current();
		if (value == null) {
			return null;
		}
		Matcher matcher = VALUE.matcher(value);
		if (!matcher.matches()) {
			return null;
		}
		String feature = matcher.group(1);
		if (feature != null && !feature.equals(featureId)) {
			return null;
		}
		return matcher.group(2);
	}

	/**
	 * 작업을 맡기는 지금의 값을 담아, 작업이 도는 스레드에서 같은 값이 보이게 한다.
	 * 스레드풀 스레드는 재사용되므로 끝나면 원래 값으로 되돌린다.
	 */
	public static Runnable wrap(Runnable task) {
		String captured = current();
		return () -> {
			String previous = INHERITED.get();
			set(captured);
			try {
				task.run();
			} finally {
				set(previous);
			}
		};
	}

	/** ThreadPoolTaskExecutor에 붙이는 데코레이터. execute/submit/@Async 모두 이 경로를 지난다. */
	public static TaskDecorator taskDecorator() {
		return MockErrorContext::wrap;
	}

	private static void set(String value) {
		if (value == null) {
			INHERITED.remove();
		} else {
			INHERITED.set(value);
		}
	}
}
