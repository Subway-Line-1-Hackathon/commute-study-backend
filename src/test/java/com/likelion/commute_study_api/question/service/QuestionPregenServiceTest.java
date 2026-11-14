package com.likelion.commute_study_api.question.service;

import static com.likelion.commute_study_api.question.service.QuestionTestFakes.countOf;
import static com.likelion.commute_study_api.question.service.QuestionTestFakes.fulfill;
import static com.likelion.commute_study_api.question.service.QuestionTestFakes.question;
import static com.likelion.commute_study_api.question.service.QuestionTestFakes.response;
import static com.likelion.commute_study_api.question.service.QuestionTestFakes.totalCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.GeneratedQuestion;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.Shortfall;
import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeAiClient;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeRepository;
import com.likelion.commute_study_api.testutil.CompletingWorkerExecutor;

class QuestionPregenServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

	private FakeRepository repository;
	private FakeAiClient aiClient;
	private PregenFailureTracker failureTracker;
	private QuestionPregenService service;
	private CategoryTarget category;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		aiClient = new FakeAiClient();
		failureTracker = new PregenFailureTracker();
		service = new QuestionPregenService(repository, aiClient, new QuestionProperties(), Runnable::run,
				failureTracker, new GeneratedQuestionWriter(repository), Clock.fixed(NOW, ZoneOffset.UTC));
		category = repository.addCategory(1L, "PREPARING", "READY");
	}

	@Test
	void alreadyCompletedPagesStillCommitsGeneratedResultOutsideAfterCommitResources() throws Exception {
		String url = "jdbc:h2:mem:pregen-after-commit-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
		var dataSource = new DriverManagerDataSource(url, "sa", "");
		var jdbc = new JdbcTemplate(dataSource);
		// A distinct datasource key reads from another connection even inside the AFTER_COMMIT callback.
		var independent = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
		jdbc.execute("CREATE TABLE pregen_result(id BIGINT PRIMARY KEY, status VARCHAR(20))");
		jdbc.update("INSERT INTO pregen_result VALUES (1, 'PREPARING')");
		var transactionSeen = new AtomicReference<Boolean>();
		var connectionSeen = new AtomicReference<Boolean>();
		var threadSeen = new AtomicReference<String>();
		var recordingRepository = new FakeRepository() {
			@Override
			public int markAvailable(long categoryId) {
				transactionSeen.set(TransactionSynchronizationManager.isActualTransactionActive());
				connectionSeen.set(TransactionSynchronizationManager.hasResource(dataSource));
				threadSeen.set(Thread.currentThread().getName());
				jdbc.update("UPDATE pregen_result SET status='AVAILABLE' WHERE id=?", categoryId);
				return super.markAvailable(categoryId);
			}
		};
		var target = recordingRepository.addCategory(1L, "PREPARING", "READY");
		try (var executor = new CompletingWorkerExecutor("pregen-boundary-worker")) {
			var generator = new QuestionPregenService(recordingRepository, new FakeAiClient(), new QuestionProperties(),
					executor, new PregenFailureTracker(), new GeneratedQuestionWriter(recordingRepository), Clock.fixed(NOW, ZoneOffset.UTC));
			new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx ->
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
						@Override public void afterCommit() {
							assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
							assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
							assertTrue(generator.pregenerate(target).join());
							// "Generation completed" must mean the result is visible to another reader immediately.
							assertEquals("AVAILABLE", independent.queryForObject("SELECT status FROM pregen_result WHERE id=1", String.class));
						}
					}));
			assertEquals("pregen-boundary-worker", threadSeen.get());
			assertFalse(transactionSeen.get());
			assertFalse(connectionSeen.get());
			assertEquals(40, recordingRepository.questions.size());
		}
	}

	@Test
	void 빈_단원은_한_번의_요청으로_40문제를_난이도별로_만들고_AVAILABLE이_된다() {
		boolean ok = service.pregenerate(category).join();

		assertTrue(ok);
		assertEquals(1, aiClient.requests.size());
		assertEquals(40, totalCount(aiClient.requests.get(0)));
		assertEquals(14, countOf(aiClient.requests.get(0), "OX"));
		assertEquals(13, countOf(aiClient.requests.get(0), "MC"));
		assertEquals(13, countOf(aiClient.requests.get(0), "SHORT"));
		// 난이도 비율 30/40/30 (쉬움12·보통16·어려움12)
		assertEquals(12, difficultyTotal(aiClient.requests.get(0), 1));
		assertEquals(16, difficultyTotal(aiClient.requests.get(0), 2));
		assertEquals(12, difficultyTotal(aiClient.requests.get(0), 3));
		assertEquals(40, repository.questions.size());
		assertTrue(repository.questions.stream().allMatch(q -> q.origin().equals("PREGEN")));
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void shortfall이_오면_모자란_몫만_다시_요청한다() {
		aiClient.behavior = request -> {
			if (aiClient.requests.size() == 1) {
				// 첫 요청: 보통 난이도 OX 6개 중 2개만 만들고 4개 부족
				List<GeneratedQuestion> partial = new ArrayList<>(fulfill(request).questions());
				removeN(partial, "OX", 2, 4);
				return CompletableFuture.completedFuture(response(partial, List.of(new Shortfall("OX", 2, 4))));
			}
			return CompletableFuture.completedFuture(fulfill(request));
		};

		assertTrue(service.pregenerate(category).join());

		assertEquals(2, aiClient.requests.size());
		assertEquals(List.of(new TypeRequest("OX", 2, 4)), aiClient.requests.get(1).requests());
		assertEquals(40, repository.questions.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 재요청을_다_써도_모자라면_있는_문제로_AVAILABLE이_된다() {
		aiClient.behavior = request -> CompletableFuture.completedFuture(oneShortOnly(request, 2));
		service = serviceWithRefillMax(0);

		assertTrue(service.pregenerate(category).join());

		assertEquals(1, aiClient.requests.size()); // 재요청 0회 설정
		// OX 14 + MC 13 + SHORT(쉬움4 + 어려움4 + 보통1, 보통만 모자람)
		assertEquals(36, repository.questions.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 재요청은_설정한_횟수까지만_한다() {
		aiClient.behavior = request -> CompletableFuture.completedFuture(oneShortOnly(request, 2));

		assertTrue(service.pregenerate(category).join());

		// 최초 1회 + 재요청 2회(pregen-refill-max 기본값). 보통 난이도 단답형만 매번 1개씩 늘어난다.
		assertEquals(3, aiClient.requests.size());
		assertEquals(List.of(new TypeRequest("SHORT", 2, 4)), aiClient.requests.get(1).requests());
		assertEquals(List.of(new TypeRequest("SHORT", 2, 3)), aiClient.requests.get(2).requests());
		// OX 14 + MC 13 + SHORT(쉬움4 + 어려움4 + 보통3)
		assertEquals(38, repository.questions.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 근거_쪽수가_단원_범위_밖인_문제는_버리고_그만큼_다시_요청한다() {
		aiClient.behavior = request -> {
			List<GeneratedQuestion> generated = new ArrayList<>(fulfill(request).questions());
			if (aiClient.requests.size() == 1) {
				// 단원 범위는 1~3쪽. 보통 난이도 MC 2개를 9쪽 근거로 바꾼다.
				replaceN(generated, "MC", 2, 2, 9);
			}
			return CompletableFuture.completedFuture(response(generated, List.of()));
		};

		assertTrue(service.pregenerate(category).join());

		assertEquals(2, aiClient.requests.size());
		assertEquals(List.of(new TypeRequest("MC", 2, 2)), aiClient.requests.get(1).requests());
		assertEquals(40, repository.questions.size());
	}

	@Test
	void AI_호출이_실패하면_PREPARING으로_두고_실패를_기록한다() {
		aiClient.behavior = request -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_UNAVAILABLE));

		boolean ok = service.pregenerate(category).join();

		assertFalse(ok);
		assertEquals("PREPARING", repository.statusOf(1L));
		assertEquals(1, failureTracker.get(1L).orElseThrow().count());
		assertEquals(NOW, failureTracker.get(1L).orElseThrow().lastFailedAt());
		assertFalse(service.isInFlight(1L));
	}

	@Test
	void AI_타임아웃도_실패로_처리한다() {
		aiClient.behavior = request -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_TIMEOUT));

		assertFalse(service.pregenerate(category).join());

		assertEquals("PREPARING", repository.statusOf(1L));
		assertTrue(failureTracker.get(1L).isPresent());
	}

	@Test
	void 성공하면_이전_실패_기록을_지운다() {
		failureTracker.recordFailure(1L, NOW);

		assertTrue(service.pregenerate(category).join());

		assertTrue(failureTracker.get(1L).isEmpty());
	}

	@Test
	void 이미_문제가_다_있으면_AI를_부르지_않고_AVAILABLE만_바꾼다() {
		repository.seedQuestions(1L, "OX", 1, 4);
		repository.seedQuestions(1L, "MC", 1, 4);
		repository.seedQuestions(1L, "SHORT", 1, 4);
		repository.seedQuestions(1L, "OX", 2, 6);
		repository.seedQuestions(1L, "MC", 2, 5);
		repository.seedQuestions(1L, "SHORT", 2, 5);
		repository.seedQuestions(1L, "OX", 3, 4);
		repository.seedQuestions(1L, "MC", 3, 4);
		repository.seedQuestions(1L, "SHORT", 3, 4);

		assertTrue(service.pregenerate(category).join());

		assertEquals(0, aiClient.requests.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 일부가_이미_있으면_나머지만_요청한다() {
		// 서버가 저장 도중 재시작된 경우 등. 보통 난이도 OX(6개)만 이미 있고 쉬움·어려움 OX는 남아있다.
		repository.seedQuestions(1L, "OX", 2, 6);

		assertTrue(service.pregenerate(category).join());

		assertEquals(8, countOf(aiClient.requests.get(0), "OX")); // 쉬움4 + 어려움4
		assertEquals(34, totalCount(aiClient.requests.get(0)));
		assertEquals(40, repository.questions.size());
	}

	@Test
	void 진행_중인_단원은_다시_시작하지_않는다() {
		CompletableFuture<QuestionGenerateResponse> pending = new CompletableFuture<>();
		aiClient.behavior = request -> pending;

		CompletableFuture<Boolean> first = service.pregenerate(category);
		boolean second = service.pregenerate(category).join();

		assertTrue(second);
		assertEquals(1, aiClient.requests.size());
		assertTrue(service.isInFlight(1L));

		pending.complete(fulfill(aiClient.requests.get(0)));
		assertTrue(first.join());
		assertFalse(service.isInFlight(1L));
	}

	@Test
	void 생성_중_자료가_삭제되면_늦게_온_결과를_저장하거나_재요청하지_않는다() {
		CompletableFuture<QuestionGenerateResponse> pending = new CompletableFuture<>();
		aiClient.behavior = request -> pending;
		var result = service.pregenerate(category);
		assertEquals(1, aiClient.requests.size());
		repository.categories.remove(category.id()); // deleted material is no longer returned
		pending.complete(fulfill(aiClient.requests.getFirst()));
		assertTrue(result.join());
		assertTrue(repository.questions.isEmpty());
		assertEquals(1, aiClient.requests.size());
	}

	@Test
	void 요청에_기존_지문을_excludeStems로_보낸다() {
		repository.seedQuestions(1L, "OX", 2, 3);

		service.pregenerate(category).join();

		assertEquals(3, aiClient.requests.get(0).excludeStems().size());
	}

	private QuestionPregenService serviceWithRefillMax(int refillMax) {
		QuestionProperties props = new QuestionProperties();
		props.setPregenRefillMax(refillMax);
		return new QuestionPregenService(repository, aiClient, props, Runnable::run,
				failureTracker, new GeneratedQuestionWriter(repository), Clock.fixed(NOW, ZoneOffset.UTC));
	}

	/** 지정한 난이도의 단답형은 요청 개수와 상관없이 1개만 만들어지는 응답. 다른 유형·난이도는 요청한 만큼 그대로 만든다. */
	private static QuestionGenerateResponse oneShortOnly(QuestionGenerateRequest request, int targetDifficulty) {
		List<GeneratedQuestion> generated = new ArrayList<>(fulfill(request).questions());
		long shortCount = generated.stream()
				.filter(q -> q.type().equals("SHORT") && q.difficulty() == targetDifficulty)
				.count();
		int shortfallCount = (int) Math.max(0, shortCount - 1);
		removeN(generated, "SHORT", targetDifficulty, shortfallCount);
		List<Shortfall> shortfall = shortfallCount > 0
				? List.of(new Shortfall("SHORT", targetDifficulty, shortfallCount))
				: List.of();
		return response(generated, shortfall);
	}

	private static int difficultyTotal(QuestionGenerateRequest request, int difficulty) {
		return request.requests().stream()
				.filter(r -> r.difficulty() == difficulty)
				.mapToInt(TypeRequest::count)
				.sum();
	}

	private static void removeN(List<GeneratedQuestion> questions, String type, int difficulty, int n) {
		for (int i = 0; i < n; i++) {
			questions.remove(questions.stream()
					.filter(q -> q.type().equals(type) && q.difficulty() == difficulty)
					.findFirst().orElseThrow());
		}
	}

	private static void replaceN(List<GeneratedQuestion> questions, String type, int difficulty, int n, int sourcePage) {
		for (int i = 0, replaced = 0; i < questions.size() && replaced < n; i++) {
			if (questions.get(i).type().equals(type) && questions.get(i).difficulty() == difficulty) {
				questions.set(i, question(type, difficulty, sourcePage));
				replaced++;
			}
		}
	}
}
