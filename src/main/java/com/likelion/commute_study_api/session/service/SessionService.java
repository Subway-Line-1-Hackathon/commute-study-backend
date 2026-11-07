package com.likelion.commute_study_api.session.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.config.CommuteProperties;
import com.likelion.commute_study_api.common.config.WrongNoteProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.question.service.QuestionGenerationService;
import com.likelion.commute_study_api.session.SessionErrorCode;
import com.likelion.commute_study_api.session.dto.CreateSessionRequest;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.SessionQuestion;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.Shortage;
import com.likelion.commute_study_api.session.dto.SessionSource;
import com.likelion.commute_study_api.session.repository.SessionRepository;
import com.likelion.commute_study_api.session.repository.SessionRepository.CandidateQuestion;
import com.likelion.commute_study_api.session.repository.SessionRepository.SessionCategory;
import com.likelion.commute_study_api.session.service.QuestionSelector.Selection;
import com.likelion.commute_study_api.session.service.SessionPlanCalculator.SessionPlan;
import com.likelion.commute_study_api.user.UserRepository;
import com.likelion.commute_study_api.user.UserRepository.UserSnapshot;

/**
 * 오늘의 커리큘럼 생성(POST /api/sessions, 슬라이스 12).
 *
 * <ol>
 *   <li>검증: 설정·과목(SETUP_REQUIRED), 단원 소유(404), 준비 중 단원(CATEGORY_NOT_READY)</li>
 *   <li>문제 수 계산 → 후보 조회 → 선택</li>
 *   <li>모자라면 단원별 추가 생성(AI-02)을 최대 ai.timeout.ai02-extra초 기다린 뒤 다시 선택.
 *       실패·시간 초과여도 있는 문제로 진행한다</li>
 *   <li>저장(이전 READY 세션 삭제 + session + session_question)만 SessionWriter의 트랜잭션으로 묶는다</li>
 * </ol>
 */
@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);
	private static final String SUMMARY_SEPARATOR = "\n\n";

	private final CurrentUser currentUser;
	private final UserRepository userRepository;
	private final SessionRepository sessionRepository;
	private final SessionPlanCalculator calculator;
	private final QuestionSelector selector;
	private final QuestionGenerationService generationService;
	private final SessionWriter writer;
	private final AiProperties aiProperties;
	private final CommuteProperties commuteProperties;
	private final WrongNoteProperties wrongNoteProperties;

	public SessionService(CurrentUser currentUser, UserRepository userRepository, SessionRepository sessionRepository,
			SessionPlanCalculator calculator, QuestionSelector selector, QuestionGenerationService generationService,
			SessionWriter writer, AiProperties aiProperties, CommuteProperties commuteProperties,
			WrongNoteProperties wrongNoteProperties) {
		this.currentUser = currentUser;
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.calculator = calculator;
		this.selector = selector;
		this.generationService = generationService;
		this.writer = writer;
		this.aiProperties = aiProperties;
		this.commuteProperties = commuteProperties;
		this.wrongNoteProperties = wrongNoteProperties;
	}

	public CreateSessionResponse create(Jwt jwt, CreateSessionRequest request) {
		long start = System.currentTimeMillis();
		UUID userId = currentUser.ensureUser(jwt);
		UserSnapshot user = userRepository.findById(userId);
		validateSetup(user);
		validateOverride(request.studyMinOverride());

		List<Long> categoryIds = request.categoryIdsOrEmpty();
		List<SessionCategory> categories = findCategories(userId, categoryIds);

		SessionPlan plan = calculator.plan(user.oneWayMin(), request.studyMinOverride(), request.questionTypes(),
				request.skipSummaryOrFalse());
		List<String> types = List.copyOf(plan.countsByType().keySet());
		int difficulty = request.difficulty();

		Selection selection = select(userId, categoryIds, types, difficulty, plan, request.source());

		ApiException generationFailure = null;
		if (needsGeneration(plan, request.source(), selection)) {
			GenerationResult result = generateMissing(selection, categories, difficulty);
			generationFailure = result.failure();
			if (result.saved() > 0) {
				selection = select(userId, categoryIds, types, difficulty, plan, request.source());
			}
		}

		List<CandidateQuestion> picked = selection.questions();
		if (picked.isEmpty()) {
			if (plan.mode() == StudyMode.REVIEW && request.source() == SessionSource.CATEGORIES) {
				// 신규 사용자 등 복습할 문제가 없다: 세션 없이 짧은 요약 하나와 안내 플래그만 준다(팀 결정, 9.3).
				log.info("[세션] 복습할 문제 없음 userId={}", userId);
				return new CreateSessionResponse(null, plan.mode(), plan.studyMin(), 0,
						firstSummary(categories), null, true, List.of());
			}
			if (generationFailure != null) {
				throw generationFailure;
			}
			throw new ApiException(SessionErrorCode.NO_QUESTIONS_AVAILABLE);
		}

		long sessionId = writer.save(userId, categoryIds, types, difficulty, plan.studyMin(), plan.mode(),
				picked.stream().map(CandidateQuestion::id).toList());

		Shortage shortage = picked.size() < plan.questionCount()
				? new Shortage(plan.questionCount(), picked.size(), selection.allSolved())
				: null;
		log.info("[세션] 생성 sessionId={} mode={} target={} picked={} elapsedMs={}",
				sessionId, plan.mode(), plan.questionCount(), picked.size(), System.currentTimeMillis() - start);

		return new CreateSessionResponse(sessionId, plan.mode(), plan.studyMin(), picked.size(),
				summary(plan, request, categories), shortage, null,
				picked.stream().map(SessionService::toResponse).toList());
	}

	// ---- 검증 ----

	private static void validateSetup(UserSnapshot user) {
		if (user.nickname() == null || user.oneWayMin() == null || !user.hasSubject()) {
			throw new ApiException(SessionErrorCode.SETUP_REQUIRED);
		}
	}

	private void validateOverride(Integer studyMinOverride) {
		if (studyMinOverride != null && studyMinOverride > commuteProperties.getMaxMinutes()) {
			throw new ApiException(CommonErrorCode.VALIDATION_ERROR,
					"studyMinOverride는 " + commuteProperties.getMaxMinutes() + "분 이하여야 합니다.");
		}
	}

	private List<SessionCategory> findCategories(UUID userId, List<Long> categoryIds) {
		List<SessionCategory> categories = sessionRepository.findOwnedCategories(userId, categoryIds);
		if (categories.size() != categoryIds.size()) {
			throw new ApiException(CommonErrorCode.NOT_FOUND, "단원을 찾을 수 없습니다.");
		}
		List<Long> preparing = categories.stream().filter(SessionCategory::isPreparing).map(SessionCategory::id).toList();
		if (!preparing.isEmpty()) {
			throw new ApiException(SessionErrorCode.CATEGORY_NOT_READY,
					SessionErrorCode.CATEGORY_NOT_READY.getMessage(), Map.of("categoryIds", preparing));
		}
		return categories;
	}

	// ---- 선택과 추가 생성 ----

	private Selection select(UUID userId, List<Long> categoryIds, List<String> types, int difficulty,
			SessionPlan plan, SessionSource source) {
		List<CandidateQuestion> candidates = sessionRepository.findCandidates(userId, categoryIds, types, difficulty,
				wrongNoteProperties.getMaxOpen());
		return selector.select(plan.mode(), source, plan.countsByType(), candidates);
	}

	/** 새 문제가 필요한 건 단원을 골라 커리큘럼을 풀 때뿐이다. 복습·오답노트는 이미 푼 문제만 낸다. */
	private static boolean needsGeneration(SessionPlan plan, SessionSource source, Selection selection) {
		return plan.mode() == StudyMode.CURRICULUM && source == SessionSource.CATEGORIES
				&& selection.missingTotal() > 0;
	}

	private record GenerationResult(int saved, ApiException failure) {
	}

	/**
	 * 단원별 generateExtra를 함께 시작하고(동시 실행 수는 aiExecutor가 제한) 정해진 시간까지만 기다린다.
	 * 시간이 지나도 생성은 백그라운드에서 계속되고, 저장된 문제는 다음 세션에서 쓰인다.
	 */
	private GenerationResult generateMissing(Selection selection, List<SessionCategory> categories, int difficulty) {
		List<Long> generatable = categories.stream()
				.filter(c -> !"EXPIRED".equals(c.materialStatus()))
				.map(SessionCategory::id)
				.toList();
		Map<Long, List<TypeRequest>> requests = QuestionSelector.distributeMissing(
				selection.missingByType(), generatable, difficulty);
		if (requests.isEmpty()) {
			return new GenerationResult(0, null);
		}

		List<CompletableFuture<Integer>> futures = new ArrayList<>();
		requests.forEach((categoryId, needs) -> futures.add(generationService.generateExtra(categoryId, needs)));

		int waitSeconds = aiProperties.getTimeout().getOrDefault("ai02-extra", 60);
		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(waitSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			log.warn("[세션] 추가 생성 대기 시간 초과 waitSeconds={} — 있는 문제로 세션을 만든다", waitSeconds);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			// 일부 단원이 실패해도 나머지 결과는 아래에서 모은다.
		}

		int saved = 0;
		ApiException failure = null;
		boolean anyUnfinished = false;
		for (CompletableFuture<Integer> future : futures) {
			if (!future.isDone()) {
				anyUnfinished = true;
			} else if (future.isCompletedExceptionally()) {
				failure = toApiException(future);
			} else {
				saved += future.join();
			}
		}
		if (failure == null && anyUnfinished && saved == 0) {
			failure = new ApiException(AiErrorCode.AI_TIMEOUT);
		}
		return new GenerationResult(saved, failure);
	}

	private static ApiException toApiException(CompletableFuture<Integer> failed) {
		try {
			failed.join();
			return null;
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof ApiException apiException) {
				return apiException;
			}
			return new ApiException(AiErrorCode.AI_UNAVAILABLE);
		}
	}

	// ---- 응답 ----

	/** CURRICULUM이고 요약을 건너뛰지 않을 때만, 고른 단원 요약을 단원 순서대로 이어 붙인다. */
	private static String summary(SessionPlan plan, CreateSessionRequest request, List<SessionCategory> categories) {
		if (plan.mode() != StudyMode.CURRICULUM || request.skipSummaryOrFalse()) {
			return null;
		}
		String joined = categories.stream()
				.map(SessionCategory::summary)
				.filter(s -> s != null && !s.isBlank())
				.collect(Collectors.joining(SUMMARY_SEPARATOR));
		return joined.isEmpty() ? null : joined;
	}

	private static String firstSummary(List<SessionCategory> categories) {
		return categories.stream()
				.map(SessionCategory::summary)
				.filter(s -> s != null && !s.isBlank())
				.findFirst()
				.orElse(null);
	}

	private static SessionQuestion toResponse(CandidateQuestion q) {
		return SessionQuestion.of(q.id(), q.type(), q.stem(), q.choices(), q.sourcePage(), q.answer(), q.explanation());
	}
}
