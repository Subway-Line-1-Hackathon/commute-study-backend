package com.likelion.commute_study_api.answer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.likelion.commute_study_api.ai.judge.JudgeAiClientImpl;
import com.likelion.commute_study_api.ai.support.AiHttpSupport;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerRequest;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerResponse;
import com.likelion.commute_study_api.answer.repository.AnswerJudgeRepository.JudgeQuestion;
import com.likelion.commute_study_api.answer.service.AnswerJudgeServiceTest.FakeQuestions;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.user.UserRepository;
import com.sun.net.httpserver.HttpServer;

/**
 * 단답 판정의 AI 실패 경로를 실제 HTTP 호출까지 확인한다.
 * 로컬 HttpServer가 AI 서버 mock(AI_MOCK=true)처럼 동작한다: X-Mock-Error가 있으면 그 에러를, 없으면 고정 성공 응답을 준다.
 * 사용자 요청의 X-Mock-Error → (ai.mock-error-passthrough) → AI 서버 → AiHttpSupport 에러 변환 → {correct: null, timeout: true}
 */
class AnswerJudgeMockErrorTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();

	private HttpServer aiMock;
	private AnswerJudgeService service;

	@BeforeEach
	void setUp() throws IOException {
		aiMock = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		aiMock.createContext("/v1/answers/judge-short", exchange -> {
			String mockError = exchange.getRequestHeaders().getFirst("X-Mock-Error");
			int status;
			String body;
			if ("LLM_TIMEOUT".equals(mockError)) {
				status = 504;
				body = "{\"error\":{\"code\":\"LLM_TIMEOUT\",\"message\":\"AI 응답 시간 초과\"}}";
			} else if (mockError != null) {
				status = 502;
				body = "{\"error\":{\"code\":\"" + mockError + "\",\"message\":\"mock 에러\"}}";
			} else {
				status = 200;
				body = "{\"correct\":true,\"reason\":\"큐를 뜻하는 다른 표현이에요.\"}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		aiMock.start();

		AiProperties props = new AiProperties();
		props.setServerUrl("http://localhost:" + aiMock.getAddress().getPort());
		props.setInternalKey("test-key");
		props.setMockErrorPassthrough(true);

		FakeQuestions questions = new FakeQuestions();
		questions.add(USER, new JudgeQuestion(1, "SHORT", "먼저 넣은 데이터가 먼저 나오는 자료구조는?", "큐",
				List.of("큐", "Queue"), "큐는 FIFO 구조다."));
		UserRepository users = new UserRepository(null) {
			@Override
			public void createIfAbsent(UUID userId) {
			}
		};
		service = new AnswerJudgeService(new CurrentUser(users), questions,
				new JudgeAiClientImpl(new AiHttpSupport(props)));
	}

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		aiMock.stop(0);
	}

	private JudgeAnswerResponse judgeWithHeader(String mockError) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (mockError != null) {
			request.addHeader("X-Mock-Error", mockError);
		}
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		return service.judge(JWT, new JudgeAnswerRequest(1L, "대기열"));
	}

	@Test
	void 헤더가_없으면_mock_성공_응답() {
		assertEquals(JudgeAnswerResponse.judged(true, "큐를 뜻하는 다른 표현이에요.", "큐"), judgeWithHeader(null));
	}

	@Test
	void LLM_TIMEOUT이면_자가_판정() {
		assertEquals(JudgeAnswerResponse.timedOut("큐"), judgeWithHeader("LLM_TIMEOUT"));
	}

	@Test
	void LLM_FAILED면_자가_판정() {
		assertEquals(JudgeAnswerResponse.timedOut("큐"), judgeWithHeader("LLM_FAILED"));
	}

	@Test
	void 정답_목록과_일치하면_헤더가_있어도_AI를_부르지_않는다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-Mock-Error", "LLM_FAILED");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		assertEquals(JudgeAnswerResponse.matched("큐"), service.judge(JWT, new JudgeAnswerRequest(1L, "Queue")));
	}
}
