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
import com.likelion.commute_study_api.session.SessionStatus;

/**
 * 통학 후 AI 피드백(슬라이스 15, AI-06)을 만들어 session.feedback에 저장한다.
 * <ul>
 * <li>COMPLETED/ENDED 세션이고, 채점된 답이 1개 이상이고, 피드백이 아직 없을 때만 AI를 부른다</li>
 * <li>AI 실패·시간 초과는 재시도 없이 feedback을 null로 둔다. 숫자 요약(#32)은 피드백 없이도 동작한다</li>
 * <li>예외를 밖으로 던지지 않는다 — 비동기 리스너에서 불리므로 던져도 받을 곳이 없다</li>
 * <li>트랜잭션 없음: AI를 기다리는 동안 DB 연결을 잡지 않는다. 중복 저장은 saveIfAbsent가 막는다</li>
 * </ul>
 * "새로 시작"·다음 날 자동 종료로 끝난 세션을 거르는 것은 이벤트 리스너가 한다(reason은 DB에 없다).
 */
@Service
public class SessionFeedbackService {

	private static final Logger log = LoggerFactory.getLogger(SessionFeedbackService.class);

	public enum Outcome {
		SAVED,
		NOT_FOUND,
		NOT_FINISHED,
		ALREADY_EXISTS,
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
			return Outcome.AI_FAILED;
		}
		log.info("[AI-06] 피드백 처리 sessionId={} result={}", sessionId, outcome);
		return outcome;
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
		if (source.feedback() != null) {
			return Outcome.ALREADY_EXISTS;
		}
		if (source.answered() == 0) {
			// AI 서버는 answered가 0이면 400을 준다
			return Outcome.NO_ANSWERS;
		}

		FeedbackResponse response;
		try {
			response = feedbackAiClient.feedback(toRequest(source));
		} catch (RuntimeException e) {
			// ApiException(AI_TIMEOUT/AI_UNAVAILABLE 등)은 AiHttpSupport가 이미 원인을 로그로 남겼다
			log.warn("[AI-06] AI 실패로 피드백 없이 둠 sessionId={} cause={}", sessionId, e.getMessage());
			return Outcome.AI_FAILED;
		}
		if (response == null || response.feedback() == null || response.feedback().isBlank()) {
			return Outcome.EMPTY_RESPONSE;
		}
		return repository.saveIfAbsent(sessionId, response.feedback().strip()) ? Outcome.SAVED : Outcome.SAVED_BY_OTHER;
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