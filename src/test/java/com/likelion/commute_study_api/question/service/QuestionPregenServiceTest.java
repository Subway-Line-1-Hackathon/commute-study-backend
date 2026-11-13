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
	void 빈_단원은_한_번의_요청으로_40문제를_만들고_AVAILABLE이_된다() {
		boolean ok = service.pregenerate(category).join();

		assertTrue(ok);
		assertEquals(1, aiClient.requests.size());
		assertEquals(40, totalCount(aiClient.requests.get(0)));
		assertEquals(14, countOf(aiClient.requests.get(0), "OX"));
		assertEquals(13, countOf(aiClient.requests.get(0), "MC"));
		assertEquals(13, countOf(aiClient.requests.get(0), "SHORT"));
		assertTrue(aiClient.requests.get(0).requests().stream().allMatch(r -> r.difficulty() == 2));
		assertEquals(40, repository.questions.size());
		assertTrue(repository.questions.stream().allMatch(q -> q.origin().equals("PREGEN")));
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void shortfall이_오면_모자란_몫만_다시_요청한다() {
		aiClient.behavior = request -> {
			if (aiClient.requests.size() == 1) {
				// 첫 요청: OX 14개 중 10개만 만들고 4개 부족
				List<GeneratedQuestion> partial = new ArrayList<>(fulfill(request).questions());
				removeN(partial, "OX", 4);
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
		aiClient.behavior = request -> CompletableFuture.completedFuture(oneShortOnly(request));
		service = serviceWithRefillMax(0);

		assertTrue(service.pregenerate(category).join());

		assertEquals(1, aiClient.requests.size()); // 재요청 0회 설정
		assertEquals(28, repository.questions.size()); // OX 14 + MC 13 + SHORT 1
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 재요청은_설정한_횟수까지만_한다() {
		aiClient.behavior = request -> CompletableFuture.completedFuture(oneShortOnly(request));

		assertTrue(service.pregenerate(category).join());

		// 최초 1회 + 재요청 2회(pregen-refill-max 기본값). 단답형이 매번 1개씩만 늘어난다.
		assertEquals(3, aiClient.requests.size());
		assertEquals(List.of(new TypeRequest("SHORT", 2, 12)), aiClient.requests.get(1).requests());
		assertEquals(List.of(new TypeRequest("SHORT", 2, 11)), aiClient.requests.get(2).requests());
		assertEquals(30, repository.questions.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 근거_쪽수가_단원_범위_밖인_문제는_버리고_그만큼_다시_요청한다() {
		aiClient.behavior = request -> {
			List<GeneratedQuestion> generated = new ArrayList<>(fulfill(request).questions());
			if (aiClient.requests.size() == 1) {
				// 단원 범위는 1~3쪽. MC 2개를 9쪽 근거로 바꾼다.
				replaceN(generated, "MC", 2, 9);
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
		repository.seedQuestions(1L, "OX", 2, 14);
		repository.seedQuestions(1L, "MC", 2, 13);
		repository.seedQuestions(1L, "SHORT", 2, 13);

		assertTrue(service.pregenerate(category).join());

		assertEquals(0, aiClient.requests.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void 일부가_이미_있으면_나머지만_요청한다() {
		// 서버가 저장 도중 재시작된 경우 등
		repository.seedQuestions(1L, "OX", 2, 14);

		assertTrue(service.pregenerate(category).join());

		assertEquals(0, countOf(aiClient.requests.get(0), "OX"));
		assertEquals(26, totalCount(aiClient.requests.get(0)));
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

	/** 단답형은 요청 개수와 상관없이 1개만 만들어지는 응답. */
	private static QuestionGenerateResponse oneShortOnly(QuestionGenerateRequest request) {
		List<GeneratedQuestion> generated = new ArrayList<>(fulfill(request).questions());
		long shortCount = generated.stream().filter(q -> q.type().equals("SHORT")).count();
		removeN(generated, "SHORT", (int) Math.max(0, shortCount - 1));
		return response(generated, List.of(new Shortfall("SHORT", 2, (int) Math.max(0, shortCount - 1))));
	}

	private static void removeN(List<GeneratedQuestion> questions, String type, int n) {
		for (int i = 0; i < n; i++) {
			questions.remove(questions.stream().filter(q -> q.type().equals(type)).findFirst().orElseThrow());
		}
	}

	private static void replaceN(List<GeneratedQuestion> questions, String type, int n, int sourcePage) {
		for (int i = 0, replaced = 0; i < questions.size() && replaced < n; i++) {
			if (questions.get(i).type().equals(type)) {
				questions.set(i, question(type, 2, sourcePage));
				replaced++;
			}
		}
	}
}
