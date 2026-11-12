package com.likelion.commute_study_api.answer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.judge.JudgeAiClient;
import com.likelion.commute_study_api.ai.judge.JudgeRequest;
import com.likelion.commute_study_api.ai.judge.JudgeResponse;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerRequest;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerResponse;
import com.likelion.commute_study_api.answer.repository.AnswerJudgeRepository;
import com.likelion.commute_study_api.answer.repository.AnswerJudgeRepository.JudgeQuestion;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.user.UserRepository;

class AnswerJudgeServiceTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();

	private FakeQuestions questions;
	private FakeJudgeAi ai;
	private AnswerJudgeService service;

	@BeforeEach
	void setUp() {
		questions = new FakeQuestions();
		ai = new FakeJudgeAi();
		UserRepository users = new UserRepository(null) {
			@Override
			public void createIfAbsent(UUID userId) {
			}
		};
		service = new AnswerJudgeService(new CurrentUser(users), questions, ai);

		questions.add(USER, new JudgeQuestion(1, "SHORT", "먼저 넣은 데이터가 먼저 나오는 자료구조는?", "큐",
				List.of("큐", "Queue"), "큐는 FIFO 구조다."));
	}

	private JudgeAnswerResponse judge(long questionId, String answer) {
		return service.judge(JWT, new JudgeAnswerRequest(questionId, answer));
	}

	// ---- AI 없이 판정 ----

	@Test
	void 정규화해서_정답_목록과_같으면_AI_없이_정답() {
		assertEquals(JudgeAnswerResponse.matched("큐"), judge(1, " QUEUE. "));
		assertEquals(JudgeAnswerResponse.matched("큐"), judge(1, "큐"));
		assertTrue(ai.calls.isEmpty());
	}

	@Test
	void 공백이나_문장부호뿐인_답은_AI_없이_오답() {
		assertEquals(JudgeAnswerResponse.blank("큐"), judge(1, ""));
		assertEquals(JudgeAnswerResponse.blank("큐"), judge(1, " ?! "));
		assertTrue(ai.calls.isEmpty());
	}

	// ---- AI 판정 ----

	@Test
	void 다르면_AI에_지문_정답목록_해설_원래_답을_보낸다() {
		ai.response = new JudgeResponse(true, "큐를 뜻하는 다른 표현이에요.");

		var result = judge(1, "대기열 ");

		assertEquals(JudgeAnswerResponse.judged(true, "큐를 뜻하는 다른 표현이에요.", "큐"), result);
		assertEquals(List.of(new JudgeRequest("먼저 넣은 데이터가 먼저 나오는 자료구조는?", List.of("큐", "Queue"),
				"큐는 FIFO 구조다.", "대기열 ")), ai.calls);
	}

	@Test
	void AI가_오답이라고_하면_오답과_이유() {
		ai.response = new JudgeResponse(false, "스택은 나중에 넣은 것이 먼저 나와요.");

		assertEquals(JudgeAnswerResponse.judged(false, "스택은 나중에 넣은 것이 먼저 나와요.", "큐"), judge(1, "스택"));
	}

	@Test
	void 정답_목록이_비어_있으면_대표_정답으로_비교하고_AI에도_보낸다() {
		questions.add(USER, new JudgeQuestion(2, "SHORT", "스택의 삽입 연산은?", "push", List.of(), null));

		assertEquals(JudgeAnswerResponse.matched("push"), judge(2, "PUSH"));

		ai.response = new JudgeResponse(false, "다른 연산이에요.");
		judge(2, "pop");
		assertEquals(new JudgeRequest("스택의 삽입 연산은?", List.of("push"), "", "pop"), ai.calls.get(0));
	}

	@Test
	void 정답_데이터가_아예_없으면_AI를_부르지_않고_자가_판정() {
		questions.add(USER, new JudgeQuestion(3, "SHORT", "지문", null, List.of(" "), "해설"));

		assertEquals(JudgeAnswerResponse.timedOut(null), judge(3, "아무 답"));
		assertTrue(ai.calls.isEmpty());
	}

	// ---- AI 실패 → 자가 판정 ----

	@Test
	void AI_시간_초과면_correct_null과_timeout() {
		ai.failure = new ApiException(AiErrorCode.AI_TIMEOUT);
		assertEquals(JudgeAnswerResponse.timedOut("큐"), judge(1, "대기열"));
	}

	@Test
	void AI_실패면_correct_null과_timeout() {
		ai.failure = new ApiException(AiErrorCode.AI_UNAVAILABLE);
		assertEquals(JudgeAnswerResponse.timedOut("큐"), judge(1, "대기열"));
	}

	@Test
	void 예상_못_한_예외도_자가_판정으로_넘기고_재시도하지_않는다() {
		ai.failure = new IllegalStateException("응답 파싱 실패");

		assertEquals(JudgeAnswerResponse.timedOut("큐"), judge(1, "대기열"));
		assertEquals(1, ai.calls.size());
	}

	// ---- 에러 ----

	@Test
	void 남의_문제나_없는_문제는_404() {
		questions.add(OTHER, new JudgeQuestion(9, "SHORT", "지문", "답", List.of("답"), "해설"));

		for (long id : new long[] {9, 99}) {
			ApiException e = assertThrows(ApiException.class, () -> judge(id, "답"));
			assertEquals(CommonErrorCode.NOT_FOUND, e.getErrorCode());
		}
		assertTrue(ai.calls.isEmpty());
	}

	@Test
	void 단답이_아닌_문제는_400() {
		questions.add(USER, new JudgeQuestion(4, "MC", "지문", "2", List.of(), "해설"));

		ApiException e = assertThrows(ApiException.class, () -> judge(4, "2"));
		assertEquals(CommonErrorCode.VALIDATION_ERROR, e.getErrorCode());
		assertTrue(ai.calls.isEmpty());
	}

	static class FakeQuestions extends AnswerJudgeRepository {
		final Map<Long, Map.Entry<UUID, JudgeQuestion>> rows = new HashMap<>();

		FakeQuestions() {
			super(null);
		}

		void add(UUID owner, JudgeQuestion question) {
			rows.put(question.id(), Map.entry(owner, question));
		}

		@Override
		public Optional<JudgeQuestion> findOwnedQuestion(UUID userId, long questionId) {
			return Optional.ofNullable(rows.get(questionId))
					.filter(row -> row.getKey().equals(userId))
					.map(Map.Entry::getValue);
		}
	}

	static class FakeJudgeAi implements JudgeAiClient {
		final List<JudgeRequest> calls = new ArrayList<>();
		JudgeResponse response = new JudgeResponse(true, "mock");
		RuntimeException failure;

		@Override
		public JudgeResponse judge(JudgeRequest request) {
			calls.add(request);
			if (failure != null) {
				throw failure;
			}
			return response;
		}
	}
}
