package com.likelion.commute_study_api.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.config.CommuteProperties;
import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.config.WrongNoteProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.exception.ErrorCode;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.question.service.QuestionGenerationService;
import com.likelion.commute_study_api.session.SessionErrorCode;
import com.likelion.commute_study_api.session.dto.CreateSessionRequest;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.dto.SessionSource;
import com.likelion.commute_study_api.session.repository.SessionRepository;
import com.likelion.commute_study_api.session.repository.SessionRepository.CandidateQuestion;
import com.likelion.commute_study_api.session.repository.SessionRepository.SessionCategory;
import com.likelion.commute_study_api.user.UserRepository;
import com.likelion.commute_study_api.user.UserRepository.UserSnapshot;

class SessionServiceTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();

	private FakeUserRepository users;
	private FakeSessionRepository sessions;
	private FakeGenerationService generation;
	private AiProperties aiProperties;
	private SessionService service;

	@BeforeEach
	void setUp() {
		users = new FakeUserRepository();
		sessions = new FakeSessionRepository();
		generation = new FakeGenerationService(sessions);
		aiProperties = new AiProperties();
		service = new SessionService(new CurrentUser(users), users, sessions,
				new SessionPlanCalculator(new StudyProperties()), new QuestionSelector(), generation,
				new SessionWriter(sessions), aiProperties, new CommuteProperties(), new WrongNoteProperties());
		sessions.addCategory(7, "AVAILABLE", "READY", "스택과 큐 요약");
	}

	private static CreateSessionRequest categories(List<Long> ids, List<String> types, Integer override) {
		return new CreateSessionRequest(SessionSource.CATEGORIES, ids, types, 2, override, null);
	}

	private static void assertError(ErrorCode expected, Runnable action) {
		ApiException e = assertThrows(ApiException.class, action::run);
		assertEquals(expected, e.getErrorCode());
	}

	// ---- 정상 경로 ----

	@Test
	void 사전_생성_문제가_충분하면_AI를_부르지_않고_만든다() {
		sessions.addUnsolved(7, "MC", 40);
		users.oneWayMin = 30;

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertNotNull(r.sessionId());
		assertEquals(StudyMode.CURRICULUM, r.mode());
		assertEquals(27, r.questionCount());
		assertEquals(27, r.questions().size());
		assertEquals("스택과 큐 요약", r.summary());
		assertNull(r.shortage());
		assertEquals(0, generation.calls.size());
		assertEquals(27, sessions.savedQuestionIds.size());
		assertEquals(SessionSource.CATEGORIES, sessions.savedSource);
		assertEquals(1, sessions.deleteReadyCalls);
	}

	@Test
	void 오늘만_바꾼_시간을_쓰고_skipSummary면_요약이_없다() {
		sessions.addUnsolved(7, "MC", 40);

		CreateSessionResponse r = service.create(JWT,
				new CreateSessionRequest(SessionSource.CATEGORIES, List.of(7L), List.of("MC"), 2, 25, true));

		assertEquals(25, r.studyMin());
		assertEquals(25, r.questionCount());
		assertNull(r.summary());
	}

	// ---- 검증 ----

	@Test
	void 닉네임이나_통학_설정이나_과목이_없으면_SETUP_REQUIRED() {
		users.nickname = null;
		assertError(SessionErrorCode.SETUP_REQUIRED, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));

		users.nickname = "소영";
		users.oneWayMin = null;
		assertError(SessionErrorCode.SETUP_REQUIRED, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));

		users.oneWayMin = 30;
		users.hasSubject = false;
		assertError(SessionErrorCode.SETUP_REQUIRED, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));
	}

	@Test
	void 내_단원이_아니면_404() {
		assertError(CommonErrorCode.NOT_FOUND, () -> service.create(JWT, categories(List.of(7L, 99L), List.of("MC"), null)));
	}

	@Test
	void 준비_중인_단원이_섞이면_CATEGORY_NOT_READY() {
		sessions.addCategory(8, "PREPARING", "READY", null);

		assertError(SessionErrorCode.CATEGORY_NOT_READY,
				() -> service.create(JWT, categories(List.of(7L, 8L), List.of("MC"), null)));
	}

	@Test
	void studyMinOverride가_통학_시간_상한을_넘으면_400() {
		assertError(CommonErrorCode.VALIDATION_ERROR, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), 121)));
	}

	// ---- 추가 생성 ----

	@Test
	void 모자라면_부족분만_추가_생성하고_다시_골라_채운다() {
		sessions.addUnsolved(7, "MC", 10);
		users.oneWayMin = 30; // 목표 27

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertEquals(List.of(Map.entry(7L, List.of(new TypeRequest("MC", 2, 17)))), generation.calls);
		assertEquals(27, r.questionCount());
		assertNull(r.shortage());
	}

	@Test
	void 여러_단원이면_부족분을_나눠_요청한다() {
		sessions.addCategory(8, "AVAILABLE", "READY", "트리 요약");
		users.oneWayMin = 30; // 목표 27, 문제 0개

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L, 8L), List.of("MC"), null));

		assertEquals(2, generation.calls.size());
		assertEquals(27, r.questionCount());
		assertEquals("스택과 큐 요약\n\n트리 요약", r.summary());
	}

	@Test
	void 추가_생성이_실패해도_있는_문제로_만들고_shortage를_준다() {
		sessions.addUnsolved(7, "MC", 10);
		users.oneWayMin = 30;
		generation.behavior = (id, needs) -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_UNAVAILABLE));

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertEquals(10, r.questionCount());
		assertEquals(new CreateSessionResponse.Shortage(27, 10, false), r.shortage());
	}

	@Test
	void 추가_생성이_실패하고_문제가_0개면_AI_에러를_그대로_준다() {
		generation.behavior = (id, needs) -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_UNAVAILABLE));

		assertError(AiErrorCode.AI_UNAVAILABLE, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));
		assertTrue(sessions.savedQuestionIds.isEmpty());
	}

	@Test
	void 추가_생성이_0개이고_문제도_0개면_NO_QUESTIONS_AVAILABLE() {
		generation.behavior = (id, needs) -> CompletableFuture.completedFuture(0); // 60개 상한 등

		assertError(SessionErrorCode.NO_QUESTIONS_AVAILABLE,
				() -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));
	}

	@Test
	void 기다리는_시간이_지나면_있는_문제로_만든다() {
		aiProperties.getTimeout().put("ai02-extra", 1);
		sessions.addUnsolved(7, "MC", 5);
		generation.behavior = (id, needs) -> new CompletableFuture<>(); // 끝나지 않는 호출

		long start = System.currentTimeMillis();
		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertTrue(System.currentTimeMillis() - start < 5_000);
		assertEquals(5, r.questionCount());
		assertNotNull(r.shortage());
	}

	@Test
	void 기다리는_시간이_지나고_문제가_0개면_AI_TIMEOUT() {
		aiProperties.getTimeout().put("ai02-extra", 1);
		generation.behavior = (id, needs) -> new CompletableFuture<>();

		assertError(AiErrorCode.AI_TIMEOUT, () -> service.create(JWT, categories(List.of(7L), List.of("MC"), null)));
	}

	@Test
	void 만료된_자료의_단원은_추가_생성하지_않는다() {
		sessions.addCategory(9, "AVAILABLE", "EXPIRED", "만료 요약");
		sessions.addUnsolved(9, "MC", 3);

		CreateSessionResponse r = service.create(JWT, categories(List.of(9L), List.of("MC"), null));

		assertEquals(0, generation.calls.size());
		assertEquals(3, r.questionCount());
		assertEquals(new CreateSessionResponse.Shortage(30, 3, false), r.shortage()); // 기본 50분 → 상한 30
	}

	@Test
	void 다_풀었으면_shortage에_allSolved() {
		sessions.addSolved(7, "MC", 5);
		generation.behavior = (id, needs) -> CompletableFuture.completedFuture(0);
		sessions.addWrong(7, "MC", true);

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertEquals(1, r.questionCount());
		assertTrue(r.shortage().allSolved());
	}

	// ---- 복습과 오답노트 ----

	@Test
	void REVIEW는_추가_생성_없이_오답과_푼_문제로_만든다() {
		users.oneWayMin = 10;
		sessions.addUnsolved(7, "MC", 10);
		sessions.addSolved(7, "MC", 3);
		sessions.addWrong(7, "MC", true);

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertEquals(StudyMode.REVIEW, r.mode());
		assertEquals(4, r.questionCount());
		assertNull(r.summary());
		assertEquals(0, generation.calls.size());
	}

	@Test
	void REVIEW인데_푼_문제가_없으면_세션_없이_noReviewItems() {
		users.oneWayMin = 10;
		sessions.addUnsolved(7, "MC", 10);

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("MC"), null));

		assertTrue(r.noReviewItems());
		assertNull(r.sessionId());
		assertEquals("스택과 큐 요약", r.summary());
		assertTrue(r.questions().isEmpty());
		assertEquals(0, sessions.deleteReadyCalls);
	}

	@Test
	void 오답노트_다시_풀기는_단원_없이_목록의_오답만() {
		sessions.addUnsolved(7, "OX", 10);
		sessions.addWrong(7, "OX", true);
		sessions.addWrong(7, "OX", false);

		CreateSessionResponse r = service.create(JWT,
				new CreateSessionRequest(SessionSource.WRONG_NOTES, null, List.of("OX"), 2, null, null));

		assertEquals(1, r.questionCount());
		assertNull(r.summary());
		assertEquals(0, generation.calls.size());
		assertNull(sessions.savedCategoryIds);
		assertEquals(SessionSource.WRONG_NOTES, sessions.savedSource);
	}

	@Test
	void 오답이_없는데_오답노트_다시_풀기면_NO_QUESTIONS_AVAILABLE() {
		assertError(SessionErrorCode.NO_QUESTIONS_AVAILABLE, () -> service.create(JWT,
				new CreateSessionRequest(SessionSource.WRONG_NOTES, null, List.of("OX"), 2, null, null)));
	}

	@Test
	void 단원_필터가_있는_오답_세션은_현재_categoryIds를_저장한다() {
		sessions.addWrong(7, "OX", true);
		CreateSessionResponse response = service.create(JWT,
				new CreateSessionRequest(SessionSource.WRONG_NOTES, List.of(7L), List.of("OX"), 2, null, null));
		assertEquals(1, response.questionCount());
		assertEquals(List.of(7L), sessions.savedCategoryIds);
		assertEquals(SessionSource.WRONG_NOTES, sessions.savedSource);
		assertEquals(0, generation.calls.size());
	}

	@Test
	void 단답은_정답을_내려주지_않는다() {
		sessions.addUnsolved(7, "SHORT", 20);

		CreateSessionResponse r = service.create(JWT, categories(List.of(7L), List.of("SHORT"), null));

		assertTrue(r.questions().stream().allMatch(q -> q.answer() == null && q.explanation() == null));
		assertFalse(r.questions().isEmpty());
	}

	// ---- 가짜들 ----

	static class FakeUserRepository extends UserRepository {
		String nickname = "소영";
		Integer oneWayMin = 50;
		boolean hasSubject = true;

		FakeUserRepository() {
			super(null);
		}

		@Override
		public void createIfAbsent(UUID userId) {
		}

		@Override
		public UserSnapshot findById(UUID userId) {
			return new UserSnapshot(userId, nickname, oneWayMin, null, null, hasSubject);
		}
	}

	static class FakeSessionRepository extends SessionRepository {
		final Map<Long, SessionCategory> categories = new HashMap<>();
		final List<CandidateQuestion> candidates = new CopyOnWriteArrayList<>();
		List<Long> savedQuestionIds = new ArrayList<>();
		List<Long> savedCategoryIds = List.of(-1L);
		SessionSource savedSource;
		int deleteReadyCalls = 0;
		private long nextId = 1;

		FakeSessionRepository() {
			super(null);
		}

		void addCategory(long id, String questionStatus, String materialStatus, String summary) {
			categories.put(id, new SessionCategory(id, "단원 " + id, summary, questionStatus, materialStatus));
		}

		void addUnsolved(long categoryId, String type, int count) {
			add(categoryId, type, count, false, false, false);
		}

		void addSolved(long categoryId, String type, int count) {
			add(categoryId, type, count, true, false, false);
		}

		void addWrong(long categoryId, String type, boolean listed) {
			add(categoryId, type, 1, true, true, listed);
		}

		private void add(long categoryId, String type, int count, boolean solved, boolean wrong, boolean listed) {
			for (int i = 0; i < count; i++) {
				long id = nextId++;
				candidates.add(new CandidateQuestion(id, categoryId, type, "stem " + id, List.of(), 1, "O", "해설",
						solved, wrong, listed, wrong ? Instant.now() : null));
			}
		}

		@Override
		public List<SessionCategory> findOwnedCategories(UUID userId, List<Long> categoryIds) {
			return categoryIds.stream().filter(categories::containsKey).map(categories::get).toList();
		}

		@Override
		public List<CandidateQuestion> findCandidates(UUID userId, List<Long> categoryIds, List<String> types,
				int difficulty, int maxListedWrongPerSubject) {
			return candidates.stream()
					.filter(q -> types.contains(q.type()))
					.filter(q -> categoryIds.isEmpty() || categoryIds.contains(q.categoryId()))
					.toList();
		}

		@Override
		public boolean lockActiveMaterialsForSelection(UUID userId, List<Long> questionIds, List<Long> categoryIds) { return true; }

		@Override
		public int deleteReadySessions(UUID userId) {
			deleteReadyCalls++;
			return 0;
		}

		@Override
		public long insertSession(UUID userId, List<Long> categoryIds, List<String> questionTypes, int difficulty,
				int studyMin, String mode, SessionSource source) {
			savedSource = source;
			savedCategoryIds = categoryIds.isEmpty() ? null : categoryIds;
			return 100L;
		}

		@Override
		public void insertSessionQuestions(long sessionId, List<Long> questionIds) {
			savedQuestionIds = questionIds;
		}
	}

	/** 기본 동작: 요청한 만큼 안 푼 문제를 저장소에 추가하고 그 수를 돌려준다(AI 서버 mock과 같은 동작). */
	static class FakeGenerationService extends QuestionGenerationService {
		final List<Map.Entry<Long, List<TypeRequest>>> calls = new CopyOnWriteArrayList<>();
		BiFunction<Long, List<TypeRequest>, CompletableFuture<Integer>> behavior;

		FakeGenerationService(FakeSessionRepository repository) {
			super(null, null, null, null, null);
			behavior = (categoryId, needs) -> {
				int saved = 0;
				for (TypeRequest need : needs) {
					repository.addUnsolved(categoryId, need.type(), need.count());
					saved += need.count();
				}
				return CompletableFuture.completedFuture(saved);
			};
		}

		@Override
		public CompletableFuture<Integer> generateExtra(long categoryId, List<TypeRequest> needs) {
			calls.add(Map.entry(categoryId, needs));
			return behavior.apply(categoryId, needs);
		}
	}
}
