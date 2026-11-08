package com.likelion.commute_study_api.feedback;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.feedback.service.SessionFeedbackService;
import com.likelion.commute_study_api.session.EndReason;

/**
 * 세션이 끝나면(SessionFinishedEvent) 통학 후 피드백을 만든다.
 * <ul>
 * <li>AFTER_COMMIT: 세션 종료·마지막 답안이 커밋된 뒤에 읽어야 하고, 롤백된 종료에는 부르지 않는다</li>
 * <li>@Async(feedbackExecutor): AI 응답(최대 15초)을 기다리지 않고 종료 응답을 바로 돌려준다</li>
 * <li>"새로 시작"·다음 날 자동 종료로 끝난 세션은 사용자가 요약 화면을 보지 않으므로 부르지 않는다(AI 명세 10장, 백엔드 명세 9.6)</li>
 * </ul>
 */
@Component
public class SessionFeedbackListener {

	private static final Logger log = LoggerFactory.getLogger(SessionFeedbackListener.class);

	private static final Set<String> SKIP_REASONS = Set.of(EndReason.NEW_START.name(), EndReason.NEXT_DAY.name());

	private final SessionFeedbackService feedbackService;

	public SessionFeedbackListener(SessionFeedbackService feedbackService) {
		this.feedbackService = feedbackService;
	}

	@Async(FeedbackAsyncConfig.FEEDBACK_EXECUTOR)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSessionFinished(SessionFinishedEvent event) {
		if (event.reason() != null && SKIP_REASONS.contains(event.reason())) {
			log.info("[AI-06] 피드백 건너뜀 sessionId={} reason={}", event.sessionId(), event.reason());
			return;
		}
		feedbackService.generate(event.sessionId());
	}
}
