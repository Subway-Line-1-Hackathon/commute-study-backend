package com.likelion.commute_study_api.question.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 단원별 문제 사전 생성(AI-02, 명세서 9.4장).
 * 어떤 스레드에서도 AI 결과를 join()으로 기다리지 않는다 — aiExecutor 스레드가 전부 대기에 묶이면
 * 정작 AI 호출을 실행할 스레드가 없어 멈추기 때문에, thenCompose로 다음 단계를 이어 붙인다.
 */
@Service
public class QuestionPregenService {

	private static final Logger log = LoggerFactory.getLogger(QuestionPregenService.class);

	static final String ORIGIN_PREGEN = "PREGEN";
	static final int EXCLUDE_STEMS_LIMIT = 60;

	private final QuestionGenerationRepository repository;
	private final QuestionAiClient questionAiClient;
	private final QuestionProperties questionProperties;
	private final Executor aiExecutor;
	private final PregenFailureTracker failureTracker;
	private final GeneratedQuestionWriter writer;
	private final Clock clock;

	// 이벤트와 재시도 스케줄러가 같은 단원을 동시에 잡지 않게 막는다(서버 메모리, 재시작하면 비워짐).
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

	@Autowired
	public QuestionPregenService(
			QuestionGenerationRepository repository,
			QuestionAiClient questionAiClient,
			QuestionProperties questionProperties,
			@Qualifier(AiAsyncConfig.AI_EXECUTOR) Executor aiExecutor,
			PregenFailureTracker failureTracker,
			GeneratedQuestionWriter writer
	) {
		this(repository, questionAiClient, questionProperties, aiExecutor, failureTracker, writer, Clock.systemUTC());
	}

	QuestionPregenService(
			QuestionGenerationRepository repository,
			QuestionAiClient questionAiClient,
			QuestionProperties questionProperties,
			Executor aiExecutor,
			PregenFailureTracker failureTracker,
			GeneratedQuestionWriter writer,
			Clock clock
	) {
		this.repository = repository;
		this.questionAiClient = questionAiClient;
		this.questionProperties = questionProperties;
		this.aiExecutor = aiExecutor;
		this.failureTracker = failureTracker;
		this.writer = writer;
		this.clock = clock;
	}

	/**
	 * 단원 하나의 사전 생성을 시작하고 바로 돌아온다.
	 * 결과: true = AVAILABLE까지 끝남, false = AI 호출 실패로 PREPARING 그대로(재시도 대상).
	 * 이미 진행 중인 단원이면 아무것도 하지 않고 true.
	 */
	public CompletableFuture<Boolean> pregenerate(CategoryTarget category) {
		if (!inFlight.add(category.id())) {
			log.info("[AI-02] 사전 생성 이미 진행 중 categoryId={}", category.id());
			return CompletableFuture.completedFuture(true);
		}
		long start = System.currentTimeMillis();

		// DB 조회부터 aiExecutor로 넘긴다: 호출한 쪽(자료 처리 요청 스레드)을 붙잡지 않고,
		// 동기 예외도 전부 이 future의 실패로 모으기 위해서다.
		return CompletableFuture
				.supplyAsync(() -> repository.findPages(category.materialId(), category.pageStart(), category.pageEnd()), aiExecutor)
				// 이미 끝난 future의 continuation도 AFTER_COMMIT의 DB 연결을 재사용하지 않게 한다.
				.thenComposeAsync(pages -> runRound(category, pages, 0), aiExecutor)
				.handle((ignored, ex) -> {
					inFlight.remove(category.id());
					long elapsedMs = System.currentTimeMillis() - start;
					if (ex != null) {
						failureTracker.recordFailure(category.id(), clock.instant());
						log.error("[AI-02] 사전 생성 실패 categoryId={} elapsedMs={} cause={}",
								category.id(), elapsedMs, rootMessage(ex));
						return false;
					}
					failureTracker.clear(category.id());
					log.info("[AI-02] 사전 생성 완료 categoryId={} elapsedMs={}", category.id(), elapsedMs);
					return true;
				});
	}

	public boolean isInFlight(long categoryId) {
		return inFlight.contains(categoryId);
	}

	private CompletableFuture<Void> runRound(CategoryTarget category, List<AiPage> pages, int round) {
		if (repository.findCategory(category.id()).isEmpty()) return CompletableFuture.completedFuture(null);
		List<TypeRequest> missing = missingRequests(category.id());
		if (missing.isEmpty()) {
			repository.markAvailable(category.id());
			return CompletableFuture.completedFuture(null);
		}
		if (round > questionProperties.getPregenRefillMax()) {
			// 재요청을 다 써도 모자라면 있는 문제로 연다(진도 분모는 실제 생성 수, 명세서 13장 임시 결정).
			log.warn("[AI-02] 재요청 후에도 부족, 있는 문제로 AVAILABLE categoryId={} missing={}", category.id(), missing);
			repository.markAvailable(category.id());
			return CompletableFuture.completedFuture(null);
		}
		if (pages.isEmpty()) {
			throw new IllegalStateException("단원 쪽 텍스트(material_page)가 없음 categoryId=" + category.id());
		}

		QuestionGenerateRequest request = new QuestionGenerateRequest(
				new QuestionGenerateRequest.Category(category.id(), category.name(), pages),
				missing,
				repository.findRecentStems(category.id(), EXCLUDE_STEMS_LIMIT)
		);
		return questionAiClient.generate(request).thenCompose(response -> {
			writer.save(category, response, ORIGIN_PREGEN);
			return runRound(category, pages, round + 1);
		});
	}

	/** 난이도·유형별 목표 개수에서 이미 있는 개수를 뺀 만큼만 요청한다. 재시작·중복 실행에도 문제가 불어나지 않는다. */
	List<TypeRequest> missingRequests(long categoryId) {
		List<TypeRequest> missing = new ArrayList<>();
		for (Map.Entry<Integer, Map<String, Integer>> byDifficulty : questionProperties.getPregenRatio().entrySet()) {
			int difficulty = byDifficulty.getKey();
			for (Map.Entry<String, Integer> entry : byDifficulty.getValue().entrySet()) {
				String type = entry.getKey().toUpperCase(Locale.ROOT);
				int need = entry.getValue() - repository.countQuestions(categoryId, type, difficulty);
				if (need > 0) {
					missing.add(new TypeRequest(type, difficulty, need));
				}
			}
		}
		return missing;
	}

	static String rootMessage(Throwable ex) {
		Throwable cur = ex;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur.getClass().getSimpleName() + ": " + cur.getMessage();
	}
}
