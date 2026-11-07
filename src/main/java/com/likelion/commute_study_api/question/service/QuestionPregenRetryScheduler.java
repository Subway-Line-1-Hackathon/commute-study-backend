package com.likelion.commute_study_api.question.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;
import com.likelion.commute_study_api.question.service.PregenFailureTracker.Failure;

/**
 * READY 자료의 PREPARING 단원을 주기적으로 다시 생성한다.
 * 실패 기록이 없으면(시드 데이터, 재시작, 이벤트 유실) 바로 시작하고, 실패한 단원은 일정 시간 뒤
 * pregen-retry-max회 재시도한 다음 포기하고 있는 문제로 AVAILABLE 처리한다.
 */
@Component
@ConditionalOnProperty(prefix = "question", name = "pregen-retry-enabled", havingValue = "true")
public class QuestionPregenRetryScheduler {

	private static final Logger log = LoggerFactory.getLogger(QuestionPregenRetryScheduler.class);

	private final QuestionGenerationRepository repository;
	private final QuestionPregenService pregenService;
	private final PregenFailureTracker failureTracker;
	private final QuestionProperties questionProperties;
	private final Clock clock;

	@Autowired
	public QuestionPregenRetryScheduler(
			QuestionGenerationRepository repository,
			QuestionPregenService pregenService,
			PregenFailureTracker failureTracker,
			QuestionProperties questionProperties
	) {
		this(repository, pregenService, failureTracker, questionProperties, Clock.systemUTC());
	}

	QuestionPregenRetryScheduler(
			QuestionGenerationRepository repository,
			QuestionPregenService pregenService,
			PregenFailureTracker failureTracker,
			QuestionProperties questionProperties,
			Clock clock
	) {
		this.repository = repository;
		this.pregenService = pregenService;
		this.failureTracker = failureTracker;
		this.questionProperties = questionProperties;
		this.clock = clock;
	}

	@Scheduled(
			initialDelayString = "${question.pregen-retry-check-interval}",
			fixedDelayString = "${question.pregen-retry-check-interval}"
	)
	public void retryPreparingCategories() {
		Instant now = clock.instant();
		for (CategoryTarget category : repository.findPreparingCategoriesOfReadyMaterials()) {
			if (pregenService.isInFlight(category.id())) {
				continue;
			}
			Optional<Failure> failure = failureTracker.get(category.id());
			if (failure.isEmpty()) {
				log.info("[AI-02] 준비 중 단원 사전 생성 시작 categoryId={}", category.id());
				pregenService.pregenerate(category);
			} else if (failure.get().count() > questionProperties.getPregenRetryMax()) {
				giveUp(category, failure.get());
			} else if (isRetryDue(failure.get(), now)) {
				log.info("[AI-02] 실패 단원 재시도 categoryId={} failures={}", category.id(), failure.get().count());
				pregenService.pregenerate(category);
			}
		}
	}

	private boolean isRetryDue(Failure failure, Instant now) {
		Duration sinceFailure = Duration.between(failure.lastFailedAt(), now);
		return sinceFailure.compareTo(questionProperties.getPregenRetryDelay()) >= 0;
	}

	private void giveUp(CategoryTarget category, Failure failure) {
		// 계속 실패하면 있는 문제로 연다(진도 분모는 실제 생성 수). 모자란 문제는 커리큘럼 생성 때 추가 생성된다.
		repository.markAvailable(category.id());
		failureTracker.clear(category.id());
		log.warn("[AI-02] 사전 생성 포기, 있는 문제로 AVAILABLE categoryId={} failures={}",
				category.id(), failure.count());
	}
}
