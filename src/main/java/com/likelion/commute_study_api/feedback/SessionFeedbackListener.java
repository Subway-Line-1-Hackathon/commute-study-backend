package com.likelion.commute_study_api.feedback;

import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <li>feedbackExecutor에 직접 제출해 제출 실패도 영속 상태로 기록한다</li>
 * <li>"새로 시작"·다음 날 자동 종료로 끝난 세션은 사용자가 요약 화면을 보지 않으므로 부르지 않는다(AI 명세 10장, 백엔드 명세 9.6)</li>
 * </ul>
 */
@Component
public class SessionFeedbackListener {

	private static final Logger log = LoggerFactory.getLogger(SessionFeedbackListener.class);

	private static final Set<String> SKIP_REASONS = Set.of(EndReason.NEW_START.name(), EndReason.NEXT_DAY.name());

	private final SessionFeedbackService feedbackService;
	private final Executor executor;

	public SessionFeedbackListener(SessionFeedbackService feedbackService,
			@Qualifier(FeedbackAsyncConfig.FEEDBACK_EXECUTOR) Executor executor) {
		this.feedbackService = feedbackService;
		this.executor = executor;
	}

	/** 종료와 생략 판정을 같은 트랜잭션으로 커밋한다. 롤백되면 상태도 남지 않는다. */
	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void prepareFeedback(SessionFinishedEvent event) {
		feedbackService.prepare(event.sessionId(), event.reason());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onSessionFinished(SessionFinishedEvent event) {
		if (event.reason() != null && SKIP_REASONS.contains(event.reason())) {
			log.info("[AI-06] 피드백 건너뜀 sessionId={} reason={}", event.sessionId(), event.reason());
			return;
		}
		try {
			executor.execute(() -> feedbackService.generate(event.sessionId()));
		} catch (RejectedExecutionException e) {
			log.warn("[AI-06] 피드백 작업 제출 실패 sessionId={}", event.sessionId());
			feedbackService.failDispatch(event.sessionId());
		}
	}
}
