package com.likelion.commute_study_api.feedback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.feedback.FeedbackAiClient;
import com.likelion.commute_study_api.ai.feedback.FeedbackRequest;
import com.likelion.commute_study_api.ai.feedback.FeedbackRequest.WrongItem;
import com.likelion.commute_study_api.ai.feedback.FeedbackResponse;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.feedback.FeedbackStatus;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository.FeedbackSource;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository.WrongQuestion;
import com.likelion.commute_study_api.feedback.service.SessionFeedbackService.Outcome;

class SessionFeedbackServiceTest {

	private static final String MOCK_FEEDBACK = "스택과 큐의 기본 개념은 잘 이해했어요.";

	private FakeRepository repository;
	private FakeAi ai;
	private SessionFeedbackService service;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		ai = new FakeAi();
		service = new SessionFeedbackService(repository, ai);
	}

	private static FeedbackSource source(long id, String status, String feedback, int answered) {
		return source(id, status, feedback, feedback == null ? FeedbackStatus.PENDING : FeedbackStatus.READY, answered);
	}

	private static FeedbackSource source(long id, String status, String feedback, FeedbackStatus feedbackStatus,
			int answered) {
		return new FeedbackSource(id, "CURRICULUM", status, feedback, feedbackStatus, 15, answered, Math.min(answered, 12),
				List.of("스택", "큐"), List.of(new WrongQuestion("다음 중 FIFO 구조는?", List.of("FIFO"))));
	}

	@Test
	void 정상_종료면_AI_요청을_보내고_피드백을_저장한다() {
		repository.put(source(1, "COMPLETED", null, 15));

		assertEquals(Outcome.SAVED, service.generate(1));

		assertEquals(MOCK_FEEDBACK, repository.saved.get(1L));
		assertEquals(FeedbackStatus.READY, repository.states.get(1L));
		assertNull(repository.reasons.get(1L));
		assertEquals(1, ai.requests.size());
		FeedbackRequest request = ai.requests.get(0);
		assertEquals(new FeedbackRequest(1L, "CURRICULUM", 15, 15, 12, List.of("스택", "큐"),
				List.of(new WrongItem("다음 중 FIFO 구조는?", List.of("FIFO")))), request);
	}

	@Test
	void ENDED_세션도_저장한다() {
		repository.put(source(1, "ENDED", null, 3));

		assertEquals(Outcome.SAVED, service.generate(1));
		assertEquals(MOCK_FEEDBACK, repository.saved.get(1L));
		assertEquals(FeedbackStatus.READY, repository.states.get(1L));
	}

	@Test
	void 피드백_앞뒤_공백은_지우고_저장한다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> new FeedbackResponse("  잘했어요.\n");

		service.generate(1);

		assertEquals("잘했어요.", repository.saved.get(1L));
	}

	@Test
	void 채점된_답이_0개면_AI를_부르지_않는다() {
		repository.put(source(1, "ENDED", null, 0));

		assertEquals(Outcome.NO_ANSWERS, service.generate(1));
		assertEquals(0, ai.requests.size());
		assertNull(repository.saved.get(1L));
		assertEquals(FeedbackStatus.SKIPPED, repository.states.get(1L));
		assertEquals("NO_GRADED_ANSWERS", repository.reasons.get(1L));
	}

	@Test
	void 이미_피드백이_있으면_AI를_부르지_않는다() {
		repository.put(source(1, "COMPLETED", "예전 피드백", 15));

		assertEquals(Outcome.ALREADY_EXISTS, service.generate(1));
		assertEquals(0, ai.requests.size());
		assertEquals(FeedbackStatus.READY, repository.states.get(1L));
	}

	@Test
	void 끝나지_않은_세션은_AI를_부르지_않는다() {
		repository.put(source(1, "IN_PROGRESS", null, 5));
		repository.put(source(2, "READY", null, 0));

		assertEquals(Outcome.NOT_FINISHED, service.generate(1));
		assertEquals(Outcome.NOT_FINISHED, service.generate(2));
		assertEquals(0, ai.requests.size());
	}

	@Test
	void 없는_세션은_건너뛴다() {
		assertEquals(Outcome.NOT_FOUND, service.generate(99));
		assertEquals(0, ai.requests.size());
	}

	@Test
	void AI_시간_초과면_재시도_없이_피드백을_비워_둔다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> {
			throw new ApiException(AiErrorCode.AI_TIMEOUT);
		};

		assertEquals(Outcome.AI_FAILED, service.generate(1));
		assertEquals(1, ai.requests.size()); // 재시도 없음
		assertNull(repository.saved.get(1L));
		assertEquals(FeedbackStatus.FAILED, repository.states.get(1L));
		assertEquals("AI_ERROR", repository.reasons.get(1L));
		assertEquals(Outcome.ALREADY_PROCESSED, service.generate(1));
		assertEquals(1, ai.requests.size());
	}

	@Test
	void AI_연결_실패여도_예외를_던지지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> {
			throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
		};

		assertEquals(Outcome.AI_FAILED, service.generate(1));
		assertNull(repository.saved.get(1L));
		assertEquals(FeedbackStatus.FAILED, repository.states.get(1L));
	}

	@Test
	void 빈_응답은_저장하지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		repository.put(source(2, "COMPLETED", null, 15));
		repository.put(source(3, "COMPLETED", null, 15));

		ai.reply = req -> null;
		assertEquals(Outcome.EMPTY_RESPONSE, service.generate(1));
		ai.reply = req -> new FeedbackResponse(null);
		assertEquals(Outcome.EMPTY_RESPONSE, service.generate(2));
		ai.reply = req -> new FeedbackResponse("   ");
		assertEquals(Outcome.EMPTY_RESPONSE, service.generate(3));

		assertEquals(Map.of(), repository.saved);
		for (long id : List.of(1L, 2L, 3L)) {
			assertEquals(FeedbackStatus.FAILED, repository.states.get(id));
			assertEquals("EMPTY_RESPONSE", repository.reasons.get(id));
		}
	}

	@Test
	void AI를_기다리는_사이_다른_처리가_먼저_저장했으면_덮어쓰지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> {
			repository.saveIfAbsent(1L, "먼저 저장된 피드백");
			return new FeedbackResponse(MOCK_FEEDBACK);
		};

		assertEquals(Outcome.SAVED_BY_OTHER, service.generate(1));
		assertEquals("먼저 저장된 피드백", repository.saved.get(1L));
		assertEquals(FeedbackStatus.READY, repository.states.get(1L));
	}

	@Test
	void 실패와_생략은_피드백_null이어도_다시_생성하지_않는다() {
		repository.put(source(1, "ENDED", null, FeedbackStatus.SKIPPED, 3));
		repository.put(source(2, "COMPLETED", null, FeedbackStatus.FAILED, 3));

		assertEquals(Outcome.ALREADY_PROCESSED, service.generate(1));
		assertEquals(Outcome.ALREADY_PROCESSED, service.generate(2));
		assertEquals(List.of(), ai.requests);
		assertEquals(FeedbackStatus.SKIPPED, repository.states.get(1L));
		assertEquals(FeedbackStatus.FAILED, repository.states.get(2L));
	}

	@Test
	void 이미_처리중인_작업은_AI를_중복_호출하지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		repository.claimPending(1);

		assertEquals(Outcome.ALREADY_PROCESSED, service.generate(1));
		assertEquals(List.of(), ai.requests);
		assertEquals(FeedbackStatus.PENDING, repository.states.get(1L));
		assertEquals("GENERATING", repository.reasons.get(1L));
	}

	@Test
	void 저장된_성공_뒤에_늦은_AI_실패가_와도_덮어쓰지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> {
			repository.saveIfAbsent(1L, "먼저 저장된 피드백");
			throw new ApiException(AiErrorCode.AI_TIMEOUT);
		};

		assertEquals(Outcome.AI_FAILED, service.generate(1));
		assertEquals("먼저 저장된 피드백", repository.saved.get(1L));
		assertEquals(FeedbackStatus.READY, repository.states.get(1L));
		assertNull(repository.reasons.get(1L));
	}

	@Test
	void AI를_기다리는_사이_종료_상태가_확정되면_성공_응답도_덮어쓰지_않는다() {
		repository.put(source(1, "COMPLETED", null, 15));
		ai.reply = req -> {
			repository.markTerminal(1, FeedbackStatus.FAILED, "PROCESSING_ERROR");
			return new FeedbackResponse(MOCK_FEEDBACK);
		};

		assertEquals(Outcome.SAVED_BY_OTHER, service.generate(1));
		assertNull(repository.saved.get(1L));
		assertEquals(FeedbackStatus.FAILED, repository.states.get(1L));
		assertEquals("PROCESSING_ERROR", repository.reasons.get(1L));
	}

	@Test
	void 종료_준비는_세션과_사유를_저장소에_전달한다() {
		service.prepare(42, "NEW_START");
		assertEquals(List.of(new Preparation(42, "NEW_START")), repository.prepared);
		assertEquals(List.of(), ai.requests);
	}

	@Test
	void 제출_실패는_진행중_또는_이미_확정된_상태를_덮지_않는다() {
		repository.put(source(1, "ENDED", null, 15));
		repository.put(source(2, "COMPLETED", null, 15));
		repository.put(source(3, "COMPLETED", "저장됨", 15));
		repository.claimPending(2);

		service.failDispatch(1);
		service.failDispatch(2);
		service.failDispatch(3);

		assertEquals(FeedbackStatus.FAILED, repository.states.get(1L));
		assertEquals("DISPATCH_REJECTED", repository.reasons.get(1L));
		assertEquals(FeedbackStatus.PENDING, repository.states.get(2L));
		assertEquals("GENERATING", repository.reasons.get(2L));
		assertEquals(FeedbackStatus.READY, repository.states.get(3L));
	}

	@Test
	void DB_오류도_밖으로_던지지_않는다() {
		FakeRepository brokenRepository = new FakeRepository() {
			@Override
			public Optional<FeedbackSource> findSource(long sessionId) {
				throw new IllegalStateException("DB 연결 실패");
			}
		};
		brokenRepository.put(source(1, "COMPLETED", null, 15));
		SessionFeedbackService broken = new SessionFeedbackService(brokenRepository, ai);

		assertEquals(Outcome.AI_FAILED, broken.generate(1));
		assertEquals(FeedbackStatus.FAILED, brokenRepository.states.get(1L));
		assertEquals("PROCESSING_ERROR", brokenRepository.reasons.get(1L));
		assertEquals(List.of(), ai.requests);
	}

	@Test
	void DB_중단으로_실패_상태도_저장하지_못해도_밖으로_던지지_않는다() {
		FakeRepository brokenRepository = new FakeRepository() {
			@Override
			public Optional<FeedbackSource> findSource(long sessionId) {
				throw new IllegalStateException("DB 연결 실패");
			}
			@Override
			public boolean markTerminal(long sessionId, FeedbackStatus status, String reason) {
				throw new IllegalStateException("DB 연결 실패");
			}
			@Override
			public boolean failDispatch(long sessionId) {
				throw new IllegalStateException("DB 연결 실패");
			}
		};
		brokenRepository.put(source(1, "COMPLETED", null, 15));
		SessionFeedbackService broken = new SessionFeedbackService(brokenRepository, ai);

		assertEquals(Outcome.AI_FAILED, broken.generate(1));
		assertDoesNotThrow(() -> broken.failDispatch(1));
		assertEquals(FeedbackStatus.PENDING, brokenRepository.states.get(1L));
		assertEquals(List.of(), ai.requests);
	}

	record Preparation(long sessionId, String reason) {
	}

	static class FakeRepository extends SessionFeedbackRepository {

		final Map<Long, FeedbackSource> sources = new HashMap<>();
		final Map<Long, String> saved = new HashMap<>();
		final Map<Long, FeedbackStatus> states = new HashMap<>();
		final Map<Long, String> reasons = new HashMap<>();
		final Set<Long> claimed = new HashSet<>();
		final List<Preparation> prepared = new ArrayList<>();

		FakeRepository() {
			super(null);
		}

		void put(FeedbackSource source) {
			sources.put(source.sessionId(), source);
			states.put(source.sessionId(), source.feedbackStatus());
		}

		@Override
		public Optional<FeedbackSource> findSource(long sessionId) {
			FeedbackSource source = sources.get(sessionId);
			if (source == null) return Optional.empty();
			return Optional.of(new FeedbackSource(source.sessionId(), source.mode(), source.status(),
					saved.getOrDefault(sessionId, source.feedback()), states.get(sessionId), source.total(),
					source.answered(), source.correct(), source.correctConcepts(), source.wrongItems()));
		}

		@Override
		public void prepare(long sessionId, String reason) {
			prepared.add(new Preparation(sessionId, reason));
		}

		@Override
		public boolean claimPending(long sessionId) {
			if (!pending(sessionId) || !claimed.add(sessionId)) return false;
			reasons.put(sessionId, "GENERATING");
			return true;
		}

		@Override
		public boolean markTerminal(long sessionId, FeedbackStatus status, String reason) {
			if (!pending(sessionId)) return false;
			states.put(sessionId, status);
			reasons.put(sessionId, reason);
			return true;
		}

		@Override
		public boolean failDispatch(long sessionId) {
			return !claimed.contains(sessionId) && markTerminal(sessionId, FeedbackStatus.FAILED, "DISPATCH_REJECTED");
		}

		@Override
		public boolean saveIfAbsent(long sessionId, String feedback) {
			if (!pending(sessionId)) return false;
			saved.put(sessionId, feedback);
			states.put(sessionId, FeedbackStatus.READY);
			reasons.remove(sessionId);
			return true;
		}

		private boolean pending(long sessionId) {
			FeedbackSource source = sources.get(sessionId);
			return source != null && List.of("COMPLETED", "ENDED").contains(source.status())
					&& source.feedback() == null && !saved.containsKey(sessionId)
					&& states.get(sessionId) == FeedbackStatus.PENDING;
		}
	}

	static class FakeAi implements FeedbackAiClient {

		final List<FeedbackRequest> requests = new ArrayList<>();
		Function<FeedbackRequest, FeedbackResponse> reply = req -> new FeedbackResponse(MOCK_FEEDBACK);

		@Override
		public FeedbackResponse feedback(FeedbackRequest request) {
			requests.add(request);
			return reply.apply(request);
		}
	}
}
