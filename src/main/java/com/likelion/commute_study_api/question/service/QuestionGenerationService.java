package com.likelion.commute_study_api.question.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.likelion.commute_study_api.ai.question.QuestionAiClient;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.ai.support.AiAsyncConfig;
import com.likelion.commute_study_api.ai.support.AiPage;
import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;

/**
 * 커리큘럼 생성(슬라이스 12)에서 문제가 모자랄 때 부족분만 추가 생성한다(AI-02, origin = EXTRA).
 * 요청은 40개 단위로 나눠 차례로 부른다 — 앞 요청에서 만든 지문을 다음 요청의 excludeStems에 넣어야
 * 중복 문제가 생기지 않기 때문에 동시에 부르지 않는다.
 */
@Service
public class QuestionGenerationService {

	private static final Logger log = LoggerFactory.getLogger(QuestionGenerationService.class);

	static final String ORIGIN_EXTRA = "EXTRA";
	// AI 서버가 한 요청에서 받는 count 합계 상한. 넘으면 400 INVALID_REQUEST.
	static final int MAX_COUNT_PER_REQUEST = 40;

	private final QuestionGenerationRepository repository;
	private final QuestionAiClient questionAiClient;
	private final QuestionProperties questionProperties;
	private final GeneratedQuestionWriter writer;
	private final Executor aiExecutor;

	public QuestionGenerationService(
			QuestionGenerationRepository repository,
			QuestionAiClient questionAiClient,
			QuestionProperties questionProperties,
			GeneratedQuestionWriter writer,
			@Qualifier(AiAsyncConfig.AI_EXECUTOR) Executor aiExecutor
	) {
		this.repository = repository;
		this.questionAiClient = questionAiClient;
		this.questionProperties = questionProperties;
		this.writer = writer;
		this.aiExecutor = aiExecutor;
	}

	private record Plan(CategoryTarget category, List<AiPage> pages, List<List<TypeRequest>> batches) {
	}

	/**
	 * 단원 하나에 needs만큼 추가 생성한다. 결과는 실제로 저장한 문제 수.
	 * PREPARING 단원, EXPIRED 자료의 단원, 60개 상한에 닿은 유형·난이도는 만들지 않는다(0 또는 일부만).
	 * AI 호출이 실패하면 future가 ApiException으로 실패한다 — 처리는 호출하는 쪽이 정한다.
	 * 이 future를 aiExecutor 스레드 안에서 join()하지 말 것(요청 스레드에서 기다리는 것은 괜찮다).
	 */
	public CompletableFuture<Integer> generateExtra(long categoryId, List<TypeRequest> needs) {
		long start = System.currentTimeMillis();
		return CompletableFuture
				.supplyAsync(() -> plan(categoryId, needs), aiExecutor)
				.thenCompose(plan -> plan.isEmpty()
						? CompletableFuture.completedFuture(0)
						: runBatches(plan.get(), 0, 0))
				.whenComplete((saved, ex) -> {
					long elapsedMs = System.currentTimeMillis() - start;
					if (ex != null) {
						log.error("[AI-02] 추가 생성 실패 categoryId={} elapsedMs={} cause={}",
								categoryId, elapsedMs, QuestionPregenService.rootMessage(ex));
					} else {
						log.info("[AI-02] 추가 생성 완료 categoryId={} saved={} elapsedMs={}", categoryId, saved, elapsedMs);
					}
				});
	}

	private Optional<Plan> plan(long categoryId, List<TypeRequest> needs) {
		Optional<CategoryTarget> found = repository.findCategory(categoryId);
		if (found.isEmpty()) {
			log.warn("[AI-02] 추가 생성 대상 단원 없음 categoryId={}", categoryId);
			return Optional.empty();
		}
		CategoryTarget category = found.get();
		if (category.isPreparing()) {
			// 사전 생성 중에 부르면 같은 문제를 중복으로 만들게 된다(AI 서버 API 명세 6장).
			log.info("[AI-02] 사전 생성 중인 단원은 추가 생성하지 않음 categoryId={}", categoryId);
			return Optional.empty();
		}
		if ("EXPIRED".equals(category.materialStatus())) {
			log.info("[AI-02] 만료된 자료의 단원은 추가 생성하지 않음 categoryId={}", categoryId);
			return Optional.empty();
		}

		List<TypeRequest> capped = applyCap(categoryId, needs);
		if (capped.isEmpty()) {
			log.info("[AI-02] 60개 상한이라 추가 생성하지 않음 categoryId={}", categoryId);
			return Optional.empty();
		}
		List<AiPage> pages = repository.findPages(category.materialId(), category.pageStart(), category.pageEnd());
		if (pages.isEmpty()) {
			throw new IllegalStateException("단원 쪽 텍스트(material_page)가 없음 categoryId=" + categoryId);
		}
		return Optional.of(new Plan(category, pages, splitByMaxCount(capped, MAX_COUNT_PER_REQUEST)));
	}

	/** 같은 유형·난이도는 합치고, 단원·난이도별 상한(60개)을 넘지 않게 줄인다. */
	private List<TypeRequest> applyCap(long categoryId, List<TypeRequest> needs) {
		Map<String, TypeRequest> merged = new LinkedHashMap<>();
		for (TypeRequest need : needs) {
			merged.merge(need.type() + ":" + need.difficulty(), need,
					(a, b) -> new TypeRequest(a.type(), a.difficulty(), a.count() + b.count()));
		}
		int max = questionProperties.getMaxPerCategoryDifficulty();
		List<TypeRequest> capped = new ArrayList<>();
		for (TypeRequest need : merged.values()) {
			int room = max - repository.countQuestions(categoryId, need.type(), need.difficulty());
			int count = Math.min(need.count(), room);
			if (count > 0) {
				capped.add(new TypeRequest(need.type(), need.difficulty(), count));
			}
		}
		return capped;
	}

	/** count 합계가 maxCount 이하가 되게 나눈다. 한 유형이 경계에 걸치면 두 요청으로 쪼갠다. */
	static List<List<TypeRequest>> splitByMaxCount(List<TypeRequest> requests, int maxCount) {
		List<List<TypeRequest>> batches = new ArrayList<>();
		List<TypeRequest> current = new ArrayList<>();
		int room = maxCount;
		for (TypeRequest request : requests) {
			int left = request.count();
			while (left > 0) {
				int take = Math.min(left, room);
				current.add(new TypeRequest(request.type(), request.difficulty(), take));
				left -= take;
				room -= take;
				if (room == 0) {
					batches.add(current);
					current = new ArrayList<>();
					room = maxCount;
				}
			}
		}
		if (!current.isEmpty()) {
			batches.add(current);
		}
		return batches;
	}

	private CompletableFuture<Integer> runBatches(Plan plan, int index, int savedSoFar) {
		if (index == plan.batches().size()) {
			return CompletableFuture.completedFuture(savedSoFar);
		}
		CategoryTarget category = plan.category();
		QuestionGenerateRequest request = new QuestionGenerateRequest(
				new QuestionGenerateRequest.Category(category.id(), category.name(), plan.pages()),
				plan.batches().get(index),
				repository.findRecentStems(category.id(), QuestionPregenService.EXCLUDE_STEMS_LIMIT)
		);
		return questionAiClient.generate(request).thenCompose(response -> {
			int saved = writer.save(category, response, ORIGIN_EXTRA);
			return runBatches(plan, index + 1, savedSoFar + saved);
		});
	}
}
