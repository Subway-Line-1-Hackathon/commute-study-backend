package com.likelion.commute_study_api.feedback.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.likelion.commute_study_api.ai.feedback.FeedbackAiClient;
import com.likelion.commute_study_api.ai.feedback.FeedbackRequest;
import com.likelion.commute_study_api.ai.feedback.FeedbackRequest.WrongItem;
import com.likelion.commute_study_api.ai.feedback.FeedbackResponse;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository.FeedbackSource;
import com.likelion.commute_study_api.feedback.FeedbackStatus;
import com.likelion.commute_study_api.session.SessionStatus;

/**
 * 통학 후 AI 피드백(슬라이스 15, AI-06)을 만들어 session.feedback에 저장한다.
 * <ul>
 * <li>COMPLETED/ENDED 세션이고, 채점된 답이 1개 이상이고, 피드백이 아직 없을 때만 AI를 부른다</li>
 * <li>성공·실패·정책상 생략을 별도 상태로 저장한다. AI 실패는 자동 재시도하지 않는다</li>
 * <li>예외를 밖으로 던지지 않는다 — 비동기 리스너에서 불리므로 던져도 받을 곳이 없다</li>
 * <li>AI를 기다리는 동안 DB 연결을 잡지 않는다. 짧은 상태 갱신은 저장소의 독립 트랜잭션이다</li>
 * </ul>
 * 생략 사유는 커밋 전에 저장하며, AFTER_COMMIT의 실행기 제출 실패도 FAILED로 기록한다.
 */
@Service
public class SessionFeedbackService {

	private static final Logger log = LoggerFactory.getLogger(SessionFeedbackService.class);

	public enum Outcome {
		SAVED,
		NOT_FOUND,
		NOT_FINISHED,
		ALREADY_EXISTS,
		ALREADY_PROCESSED,
		NO_ANSWERS,
		AI_FAILED,
		EMPTY_RESPONSE,
		/** AI를 기다리는 사이 다른 처리가 먼저 저장했다. */
		SAVED_BY_OTHER
	}

	private final SessionFeedbackRepository repository;
	private final FeedbackAiClient feedbackAiClient;

	public SessionFeedbackService(SessionFeedbackRepository repository, FeedbackAiClient feedbackAiClient) {
		this.repository = repository;
		this.feedbackAiClient = feedbackAiClient;
	}

	public Outcome generate(long sessionId) {
		Outcome outcome;
		try {
			outcome = doGenerate(sessionId);
		} catch (RuntimeException e) {
			// AI 호출 밖(DB 조회·저장)의 예상 못 한 실패. 피드백만 없을 뿐 다른 기능에는 영향이 없다.
			log.error("[AI-06] 피드백 처리 실패 sessionId={}", sessionId, e);
			failSafely(sessionId, "PROCESSING_ERROR");
			return Outcome.AI_FAILED;
		}
		log.info("[AI-06] 피드백 처리 sessionId={} result={}", sessionId, outcome);
		return outcome;
	}

	/** 리스너의 BEFORE_COMMIT에서 호출하며 세션 종료 트랜잭션에 참여한다. */
	public void prepare(long sessionId, String reason) {
		repository.prepare(sessionId, reason);
	}

	/** 제출 실패가 원래 종료 요청까지 실패시키지 않도록 최선으로 상태를 기록한다. */
	public void failDispatch(long sessionId) {
		try {
			repository.failDispatch(sessionId);
		} catch (RuntimeException e) {
			log.error("[AI-06] 피드백 제출 실패 상태 저장 실패 sessionId={}", sessionId, e);
		}
	}

	private Outcome doGenerate(long sessionId) {
		Optional<FeedbackSource> found = repository.findSource(sessionId);
		if (found.isEmpty()) {
			return Outcome.NOT_FOUND;
		}
		FeedbackSource source = found.get();
		if (!SessionStatus.valueOf(source.status()).isFinished()) {
			return Outcome.NOT_FINISHED;
		}
		if (source.feedbackStatus() == FeedbackStatus.READY) {
			return Outcome.ALREADY_EXISTS;
		}
		if (source.feedbackStatus() != FeedbackStatus.PENDING) {
			return Outcome.ALREADY_PROCESSED;
		}
		if (source.answered() == 0) {
			// AI 서버는 answered가 0이면 400을 준다
			repository.markTerminal(sessionId, FeedbackStatus.SKIPPED, "NO_GRADED_ANSWERS");
			return Outcome.NO_ANSWERS;
		}
		if (!repository.claimPending(sessionId)) {
			return Outcome.ALREADY_PROCESSED;
		}

		FeedbackResponse response;
		try {
			response = feedbackAiClient.feedback(toRequest(source));
		} catch (RuntimeException e) {
			// ApiException(AI_TIMEOUT/AI_UNAVAILABLE 등)은 AiHttpSupport가 이미 원인을 로그로 남겼다
			log.warn("[AI-06] AI 실패로 피드백 없이 둠 sessionId={} cause={}", sessionId, e.getMessage());
			failSafely(sessionId, "AI_ERROR");
			return Outcome.AI_FAILED;
		}
		if (response == null || response.feedback() == null || response.feedback().isBlank()) {
			failSafely(sessionId, "EMPTY_RESPONSE");
			return Outcome.EMPTY_RESPONSE;
		}
		return repository.saveIfAbsent(sessionId, response.feedback().strip()) ? Outcome.SAVED : Outcome.SAVED_BY_OTHER;
	}

	private void failSafely(long sessionId, String reason) {
		try {
			repository.markTerminal(sessionId, FeedbackStatus.FAILED, reason);
		} catch (RuntimeException e) {
			// DB 자체가 중단되면 상태 기록도 불가능하다. 성공/생략 상태를 추정해 바꾸지 않는다.
			log.error("[AI-06] 피드백 실패 상태 저장 실패 sessionId={}", sessionId, e);
		}
	}

	static FeedbackRequest toRequest(FeedbackSource source) {
		return new FeedbackRequest(
				source.sessionId(),
				source.mode(),
				source.total(),
				source.answered(),
				source.correct(),
				source.correctConcepts(),
				source.wrongItems().stream().map(w -> new WrongItem(w.stem(), w.conceptTags())).toList());
	}
}
