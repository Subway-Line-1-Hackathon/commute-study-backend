package com.likelion.commute_study_api.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeAiClient;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeRepository;

class QuestionPregenRetrySchedulerTest {

	private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

	private FakeRepository repository;
	private FakeAiClient aiClient;
	private PregenFailureTracker failureTracker;
	private QuestionPregenService pregenService;
	private QuestionProperties props;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		aiClient = new FakeAiClient();
		failureTracker = new PregenFailureTracker();
		props = new QuestionProperties(); // 재시도 지연 10분, 재시도 1회
		pregenService = new QuestionPregenService(repository, aiClient, props, Runnable::run,
				failureTracker, new GeneratedQuestionWriter(repository), Clock.fixed(NOW, ZoneOffset.UTC));
		repository.addCategory(1L, "PREPARING", "READY");
	}

	private QuestionPregenRetryScheduler schedulerAt(Instant now) {
		return new QuestionPregenRetryScheduler(repository, pregenService, failureTracker, props,
				Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void 실패_기록이_없는_준비_중_단원은_바로_생성한다() {
		// 시드 데이터나 서버 재시작 직후의 단원
		schedulerAt(NOW).retryPreparingCategories();

		assertEquals(1, aiClient.requests.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
	}

	@Test
	void READY가_아닌_자료의_단원은_건드리지_않는다() {
		repository.addCategory(2L, "PREPARING", "PROCESSING");

		schedulerAt(NOW).retryPreparingCategories();

		assertTrue(aiClient.requests.stream().noneMatch(r -> r.category().categoryId() == 2L));
		assertEquals("PREPARING", repository.statusOf(2L));
	}

	@Test
	void 실패한_단원은_재시도_지연이_지나기_전엔_다시_부르지_않는다() {
		failureTracker.recordFailure(1L, NOW);

		schedulerAt(NOW.plus(Duration.ofMinutes(9))).retryPreparingCategories();

		assertEquals(0, aiClient.requests.size());
	}

	@Test
	void 재시도_지연이_지나면_한_번_다시_부른다() {
		failureTracker.recordFailure(1L, NOW);

		schedulerAt(NOW.plus(Duration.ofMinutes(10))).retryPreparingCategories();

		assertEquals(1, aiClient.requests.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
		assertTrue(failureTracker.get(1L).isEmpty());
	}

	@Test
	void 재시도까지_실패하면_다음_점검에서_포기하고_있는_문제로_AVAILABLE() {
		aiClient.behavior = request -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_UNAVAILABLE));
		repository.seedQuestions(1L, "OX", 2, 5);

		// 1차 실패(이벤트로 시작했다고 가정)
		pregenService.pregenerate(repository.findCategory(1L).orElseThrow()).join();
		// 10분 뒤 재시도 → 2차 실패
		schedulerAt(NOW.plus(Duration.ofMinutes(10))).retryPreparingCategories();
		assertEquals(2, failureTracker.get(1L).orElseThrow().count());
		assertEquals("PREPARING", repository.statusOf(1L));

		// 다음 점검: 포기
		schedulerAt(NOW.plus(Duration.ofMinutes(15))).retryPreparingCategories();

		assertEquals(2, aiClient.requests.size());
		assertEquals("AVAILABLE", repository.statusOf(1L));
		assertEquals(5, repository.questions.size());
		assertTrue(failureTracker.get(1L).isEmpty());
	}

	@Test
	void 진행_중인_단원은_건너뛴다() {
		aiClient.behavior = request -> new CompletableFuture<QuestionGenerateResponse>(); // 끝나지 않는 호출
		pregenService.pregenerate(repository.findCategory(1L).orElseThrow());

		schedulerAt(NOW).retryPreparingCategories();

		assertEquals(1, aiClient.requests.size());
	}
}
