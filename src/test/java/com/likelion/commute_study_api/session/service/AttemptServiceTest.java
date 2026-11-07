package com.likelion.commute_study_api.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.exception.ErrorCode;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest;
import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest.AttemptItem;
import com.likelion.commute_study_api.session.dto.SaveAttemptsResponse;
import com.likelion.commute_study_api.session.repository.AttemptRepository;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.Progress;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.SessionRow;
import com.likelion.commute_study_api.session.service.SessionProgressServiceTest.FakeProgressRepository;
import com.likelion.commute_study_api.user.UserRepository;

class AttemptServiceTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();
	private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");
	private static final Instant T = NOW.minusSeconds(60);
	private static final long SESSION = 1;

	private FakeAttemptRepository attempts;
	private FakeProgressRepository sessions;
	private final List<Object> events = new ArrayList<>();
	private AttemptService service;

	@BeforeEach
	void setUp() {
		attempts = new FakeAttemptRepository();
		sessions = new FakeProgressRepository() {
			@Override
			public Progress countProgress(long sessionId) {
				return new Progress(attempts.saved.size(), attempts.questions.size());
			}
		};
		UserRepository users = new UserRepository(null) {
			@Override
			public void createIfAbsent(UUID userId) {
			}
		};
		service = new AttemptService(new CurrentUser(users), sessions, attempts, events::add,
				Clock.fixed(NOW, ZoneOffset.UTC));

		sessions.add(SESSION, USER, SessionStatus.IN_PROGRESS, NOW.minusSeconds(600));
		attempts.question(101, "OX", "O");
		attempts.question(102, "MC", "2");
		attempts.question(103, "SHORT", "큐");
	}

	private static AttemptItem item(long questionId, String answer, Boolean correct) {
		return new AttemptItem(questionId, answer, correct, null, T);
	}

	private SaveAttemptsResponse save(AttemptItem... items) {
		return service.save(JWT, new SaveAttemptsRequest(SESSION, List.of(items)));
	}

	private static void assertError(ErrorCode expected, Runnable action) {
		ApiException e = assertThrows(ApiException.class, action::run);
		assertEquals(expected, e.getErrorCode());
	}

	// ---- 채점 보정 ----

	@Test
	void OX_객관식은_서버가_정답을_다시_매긴다() {
		save(item(101, "X", true), item(102, " 2 ", false));

		assertEquals(false, attempts.saved.get(101L).correct()); // 앱은 맞았다고 했지만 틀림
		assertEquals(true, attempts.saved.get(102L).correct()); // 앱은 틀렸다고 했지만 맞음(공백 무시)
		assertEquals(Map.of(101L, 1), attempts.wrongCounts);
	}

	@Test
	void 예전_시드의_true_false_정답도_OX로_비교한다() {
		attempts.question(104, "OX", "true");
		attempts.question(105, "OX", "false");

		save(item(104, "o", null), item(105, "O", null));

		assertEquals(true, attempts.saved.get(104L).correct());
		assertEquals(false, attempts.saved.get(105L).correct());
	}

	@Test
	void 단답은_앱_판정과_자가_판정을_그대로_쓴다() {
		save(new AttemptItem(103L, "대기열", false, true, T));

		var saved = attempts.saved.get(103L);
		assertEquals(false, saved.correct());
		assertTrue(saved.selfJudged());
		assertEquals(Map.of(103L, 1), attempts.wrongCounts);
	}

	@Test
	void 단답_채점_대기는_오답노트에_넣지_않는다() {
		var response = save(item(103, "대기열", null));

		assertEquals(1, response.saved());
		assertNull(attempts.saved.get(103L).correct());
		assertTrue(attempts.wrongCounts.isEmpty());
	}

	// ---- 중복 ----

	@Test
	void 같은_답안을_두_번_보내도_한_번만_저장하고_오답도_한_번만_센다() {
		save(item(101, "X", null));
		var again = save(item(101, "X", null));

		assertEquals(0, again.saved());
		assertEquals(1, attempts.saved.size());
		assertEquals(Map.of(101L, 1), attempts.wrongCounts);
	}

	@Test
	void 같은_묶음_안의_중복은_첫_번째만_쓴다() {
		var response = save(item(101, "O", null), item(101, "X", null));

		assertEquals(1, response.saved());
		assertEquals("O", attempts.saved.get(101L).answer());
	}

	// ---- 오답노트 해결 ----

	@Test
	void 오답노트_다시_풀기에서_맞히면_RESOLVED() {
		sessions.sessions.put(SESSION, new SessionRow(SESSION, USER, SessionStatus.IN_PROGRESS, true, NOW, null));

		save(item(101, "O", null));

		assertEquals(List.of(101L), attempts.resolved);
	}

	@Test
	void 일반_세션에서_맞히면_오답노트를_바꾸지_않는다() {
		save(item(101, "O", null));
		assertTrue(attempts.resolved.isEmpty());
	}

	// ---- 완료 ----

	@Test
	void 마지막_문제까지_풀면_COMPLETED와_이벤트() {
		var first = save(item(101, "O", null));
		assertEquals(SessionStatus.IN_PROGRESS, first.sessionStatus());
		assertEquals(new SaveAttemptsResponse.Progress(1, 3), first.progress());
		assertTrue(events.isEmpty());

		var last = save(item(102, "2", null), item(103, "큐", true));

		assertEquals(SessionStatus.COMPLETED, last.sessionStatus());
		assertEquals(new SaveAttemptsResponse.Progress(3, 3), last.progress());
		assertEquals(SessionStatus.COMPLETED, sessions.get(SESSION).status());
		assertEquals(NOW, sessions.get(SESSION).endedAt());
		assertEquals(List.of(new SessionFinishedEvent(SESSION, USER, "COMPLETED", null)), events);
	}

	@Test
	void 끝난_세션에_늦게_온_답안도_저장하지만_상태와_이벤트는_그대로() {
		sessions.add(SESSION, USER, SessionStatus.ENDED, NOW.minusSeconds(600));

		var response = save(item(101, "X", null), item(102, "2", null), item(103, "큐", true));

		assertEquals(3, response.saved());
		assertEquals(SessionStatus.ENDED, response.sessionStatus());
		assertEquals(Map.of(101L, 1), attempts.wrongCounts);
		assertTrue(events.isEmpty());
	}

	// ---- 에러 ----

	@Test
	void 출발_전_세션은_409() {
		sessions.add(SESSION, USER, SessionStatus.READY, null);
		assertError(CommonErrorCode.STATE_CONFLICT, () -> save(item(101, "O", null)));
	}

	@Test
	void 남의_세션은_404() {
		sessions.add(SESSION, OTHER, SessionStatus.IN_PROGRESS, NOW);
		assertError(CommonErrorCode.NOT_FOUND, () -> save(item(101, "O", null)));
	}

	@Test
	void 세션에_없는_문제가_섞이면_404이고_아무것도_저장하지_않는다() {
		ApiException e = assertThrows(ApiException.class, () -> save(item(101, "O", null), item(999, "O", null)));

		assertEquals(CommonErrorCode.NOT_FOUND, e.getErrorCode());
		assertEquals(Map.of("questionIds", List.of(999L)), e.getDetails());
		assertTrue(attempts.saved.isEmpty());
	}

	@Test
	void 기기_시계가_미래면_서버_시각으로_맞춘다() {
		save(new AttemptItem(101L, "X", null, null, NOW.plusSeconds(3600)));

		assertEquals(NOW, attempts.saved.get(101L).answeredAt());
		assertEquals(NOW, attempts.wrongAt.get(101L));
	}

	@Test
	void normalize는_공백과_대소문자를_무시한다() {
		assertEquals("O", AttemptService.normalize("OX", " true "));
		assertEquals("X", AttemptService.normalize("OX", "x"));
		assertEquals("2", AttemptService.normalize("MC", " 2"));
		assertFalse(AttemptService.normalize("MC", "TRUE").equals("O")); // O/X 변환은 O/X 문제만
	}

	/** 세션 1개의 문제·응답·오답노트를 메모리에 둔다. */
	static class FakeAttemptRepository extends AttemptRepository {
		final Map<Long, SessionQuestion> questions = new LinkedHashMap<>();
		final Map<Long, NewAttempt> saved = new LinkedHashMap<>();
		final Map<Long, Integer> wrongCounts = new HashMap<>();
		final Map<Long, Instant> wrongAt = new HashMap<>();
		final List<Long> resolved = new ArrayList<>();

		FakeAttemptRepository() {
			super(null);
		}

		void question(long id, String type, String answer) {
			questions.put(id, new SessionQuestion(id, type, answer));
		}

		@Override
		public List<SessionQuestion> findSessionQuestions(long sessionId, List<Long> questionIds) {
			return questionIds.stream().filter(questions::containsKey).map(questions::get).toList();
		}

		@Override
		public List<Boolean> insertIfAbsent(UUID userId, long sessionId, List<NewAttempt> attempts) {
			return attempts.stream().map(a -> saved.putIfAbsent(a.questionId(), a) == null).toList();
		}

		@Override
		public void recordWrong(UUID userId, long questionId, Instant at) {
			wrongCounts.merge(questionId, 1, Integer::sum);
			wrongAt.put(questionId, at);
		}

		@Override
		public boolean resolveWrong(UUID userId, long questionId) {
			resolved.add(questionId);
			return true;
		}
	}
}
