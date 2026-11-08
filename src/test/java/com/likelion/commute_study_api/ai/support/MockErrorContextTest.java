package com.likelion.commute_study_api.ai.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class MockErrorContextTest {

	private ThreadPoolTaskExecutor executor;

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		if (executor != null) {
			executor.shutdown();
		}
	}

	private static void inRequest(String header) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (header != null) {
			request.addHeader("X-Mock-Error", header);
		}
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}

	private ThreadPoolTaskExecutor decoratedExecutor() {
		executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setTaskDecorator(MockErrorContext.taskDecorator());
		executor.initialize();
		return executor;
	}

	/** 비동기 스레드에서 본 codeFor(featureId) 값. */
	private String codeOnWorker(String featureId) throws Exception {
		AtomicReference<String> seen = new AtomicReference<>("(not run)");
		CompletableFuture.runAsync(() -> seen.set(MockErrorContext.codeFor(featureId)), executor)
				.get(2, TimeUnit.SECONDS);
		return seen.get();
	}

	// ---- 값 형식 ----

	@Test
	void 코드만_보내면_모든_기능에_붙는다() {
		inRequest("LLM_TIMEOUT");
		assertEquals("LLM_TIMEOUT", MockErrorContext.codeFor("AI-01"));
		assertEquals("LLM_TIMEOUT", MockErrorContext.codeFor("AI-06"));
	}

	@Test
	void 기능을_지정하면_그_기능에만_붙는다() {
		inRequest("AI-02:LLM_FAILED");
		assertEquals("LLM_FAILED", MockErrorContext.codeFor("AI-02"));
		assertNull(MockErrorContext.codeFor("AI-01"));
	}

	@Test
	void 형식이_틀리면_무시한다() {
		for (String bad : new String[] { "llm_timeout", "LLM TIMEOUT", "AI-07:LLM_FAILED", "AI-02:", ":LLM_FAILED",
				"AI-02:LLM_FAILED:X", "LLM_TIMEOUT; x=1", "" }) {
			inRequest(bad);
			assertNull(MockErrorContext.codeFor("AI-02"), bad);
		}
	}

	@Test
	void 요청도_넘겨받은_값도_없으면_null() {
		assertNull(MockErrorContext.codeFor("AI-01"));
		inRequest(null);
		assertNull(MockErrorContext.codeFor("AI-01"));
	}

	// ---- 비동기 전달 ----

	@Test
	void 작업을_맡긴_요청의_값이_비동기_스레드로_넘어간다() throws Exception {
		decoratedExecutor();
		inRequest("UNSUPPORTED_FILE");

		assertEquals("UNSUPPORTED_FILE", codeOnWorker("AI-01"));
	}

	@Test
	void 비동기_스레드에서_다시_맡긴_작업에도_넘어간다() throws Exception {
		// 업로드 → 단원화(aiExecutor) → 완료 후 사전 생성(aiExecutor) 흐름
		decoratedExecutor();
		ThreadPoolTaskExecutor second = new ThreadPoolTaskExecutor();
		second.setTaskDecorator(MockErrorContext.taskDecorator());
		second.initialize();
		try {
			inRequest("AI-02:LLM_FAILED");
			AtomicReference<String> seen = new AtomicReference<>();
			CompletableFuture.runAsync(() -> {
				RequestContextHolder.resetRequestAttributes(); // 작업 스레드에는 요청이 없다
				CompletableFuture.runAsync(() -> seen.set(MockErrorContext.codeFor("AI-02")), second).join();
			}, executor).get(2, TimeUnit.SECONDS);

			assertEquals("LLM_FAILED", seen.get());
		} finally {
			second.shutdown();
		}
	}

	@Test
	void 작업이_끝나면_스레드의_값을_지워_다음_작업에_남지_않는다() throws Exception {
		decoratedExecutor();
		inRequest("LLM_TIMEOUT");
		assertEquals("LLM_TIMEOUT", codeOnWorker("AI-01"));

		RequestContextHolder.resetRequestAttributes(); // 스케줄러처럼 요청 없이 맡긴 작업
		assertNull(codeOnWorker("AI-01"));
	}

	@Test
	void 앱의_aiExecutor와_feedbackExecutor에_데코레이터가_붙어_있다() throws Exception {
		inRequest("LLM_FAILED");
		var ai = (ThreadPoolTaskExecutor) new AiAsyncConfig().aiExecutor(new com.likelion.commute_study_api.common.config.AiProperties());
		var feedback = (ThreadPoolTaskExecutor) new com.likelion.commute_study_api.feedback.FeedbackAsyncConfig().feedbackExecutor();
		try {
			for (ThreadPoolTaskExecutor app : new ThreadPoolTaskExecutor[] { ai, feedback }) {
				AtomicReference<String> seen = new AtomicReference<>();
				CompletableFuture.runAsync(() -> seen.set(MockErrorContext.codeFor("AI-01")), app).get(2, TimeUnit.SECONDS);
				assertEquals("LLM_FAILED", seen.get(), app.getThreadNamePrefix());
			}
		} finally {
			ai.shutdown();
			feedback.shutdown();
		}
	}

	@Test
	void 헤더_없는_요청이_맡긴_작업에는_값이_없다() throws Exception {
		decoratedExecutor();
		inRequest(null);
		assertNull(codeOnWorker("AI-06"));
	}
}
