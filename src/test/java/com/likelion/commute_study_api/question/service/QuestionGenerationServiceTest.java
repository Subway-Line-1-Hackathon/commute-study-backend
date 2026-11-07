package com.likelion.commute_study_api.question.service;

import static com.likelion.commute_study_api.question.service.QuestionTestFakes.totalCount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeAiClient;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeRepository;

class QuestionGenerationServiceTest {

	private FakeRepository repository;
	private FakeAiClient aiClient;
	private QuestionGenerationService service;

	@BeforeEach
	void setUp() {
		repository = new FakeRepository();
		aiClient = new FakeAiClient();
		service = new QuestionGenerationService(repository, aiClient, new QuestionProperties(),
				new GeneratedQuestionWriter(repository), Runnable::run);
		repository.addCategory(1L, "AVAILABLE", "READY");
	}

	@Test
	void 부족분만큼_EXTRA로_저장한다() {
		int saved = service.generateExtra(1L, List.of(new TypeRequest("MC", 3, 5))).join();

		assertEquals(5, saved);
		assertEquals(List.of(new TypeRequest("MC", 3, 5)), aiClient.requests.get(0).requests());
		assertTrue(repository.questions.stream().allMatch(q -> q.origin().equals("EXTRA")));
	}

	@Test
	void 사십개를_넘으면_나눠서_차례로_부르고_앞에서_만든_지문을_제외한다() {
		int saved = service.generateExtra(1L, List.of(new TypeRequest("OX", 1, 50))).join();

		assertEquals(50, saved);
		assertEquals(2, aiClient.requests.size());
		assertEquals(40, totalCount(aiClient.requests.get(0)));
		assertEquals(10, totalCount(aiClient.requests.get(1)));
		// 두 번째 요청의 excludeStems에 첫 요청에서 만든 지문 40개가 들어 있어야 중복이 안 생긴다
		assertEquals(40, aiClient.requests.get(1).excludeStems().size());
		List<String> firstBatchStems = repository.questions.subList(0, 40).stream().map(q -> q.stem()).toList();
		assertTrue(aiClient.requests.get(1).excludeStems().containsAll(firstBatchStems));
	}

	@Test
	void 단원_난이도별_60개_상한을_넘지_않게_줄인다() {
		repository.seedQuestions(1L, "MC", 3, 55);

		int saved = service.generateExtra(1L, List.of(new TypeRequest("MC", 3, 10))).join();

		assertEquals(5, saved);
		assertEquals(List.of(new TypeRequest("MC", 3, 5)), aiClient.requests.get(0).requests());
		assertEquals(60, repository.countQuestions(1L, "MC", 3));
	}

	@Test
	void 상한에_닿으면_AI를_부르지_않는다() {
		repository.seedQuestions(1L, "MC", 3, 60);

		int saved = service.generateExtra(1L, List.of(new TypeRequest("MC", 3, 10))).join();

		assertEquals(0, saved);
		assertEquals(0, aiClient.requests.size());
	}

	@Test
	void 상한은_난이도별로_따로_센다() {
		repository.seedQuestions(1L, "MC", 2, 60);

		int saved = service.generateExtra(1L, List.of(new TypeRequest("MC", 3, 10))).join();

		assertEquals(10, saved);
	}

	@Test
	void 같은_유형_난이도는_합쳐서_상한을_검사한다() {
		repository.seedQuestions(1L, "SHORT", 1, 55);

		int saved = service.generateExtra(1L,
				List.of(new TypeRequest("SHORT", 1, 4), new TypeRequest("SHORT", 1, 4))).join();

		assertEquals(5, saved);
	}

	@Test
	void 사전_생성_중인_단원은_추가_생성하지_않는다() {
		repository.addCategory(2L, "PREPARING", "READY");

		assertEquals(0, service.generateExtra(2L, List.of(new TypeRequest("OX", 2, 5))).join());
		assertEquals(0, aiClient.requests.size());
	}

	@Test
	void 만료된_자료의_단원은_추가_생성하지_않는다() {
		repository.addCategory(3L, "AVAILABLE", "EXPIRED");

		assertEquals(0, service.generateExtra(3L, List.of(new TypeRequest("OX", 2, 5))).join());
		assertEquals(0, aiClient.requests.size());
	}

	@Test
	void 없는_단원이면_0을_돌려준다() {
		assertEquals(0, service.generateExtra(999L, List.of(new TypeRequest("OX", 2, 5))).join());
	}

	@Test
	void AI_호출이_실패하면_future가_ApiException으로_실패한다() {
		aiClient.behavior = request -> CompletableFuture.failedFuture(new ApiException(AiErrorCode.AI_TIMEOUT));

		CompletionException e = assertThrows(CompletionException.class,
				() -> service.generateExtra(1L, List.of(new TypeRequest("OX", 2, 5))).join());

		ApiException cause = assertInstanceOf(ApiException.class, e.getCause());
		assertEquals(AiErrorCode.AI_TIMEOUT, cause.getErrorCode());
	}

	@Test
	void splitByMaxCount는_합계가_상한을_넘지_않게_나눈다() {
		List<List<TypeRequest>> batches = QuestionGenerationService.splitByMaxCount(
				List.of(new TypeRequest("OX", 2, 30), new TypeRequest("MC", 2, 20)), 40);

		assertEquals(List.of(
				List.of(new TypeRequest("OX", 2, 30), new TypeRequest("MC", 2, 10)),
				List.of(new TypeRequest("MC", 2, 10))
		), batches);
	}

	@Test
	void splitByMaxCount는_딱_맞으면_빈_요청을_만들지_않는다() {
		List<List<TypeRequest>> batches = QuestionGenerationService.splitByMaxCount(
				List.of(new TypeRequest("OX", 2, 40)), 40);

		assertEquals(1, batches.size());
	}
}
