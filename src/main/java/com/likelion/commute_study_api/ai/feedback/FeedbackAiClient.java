package com.likelion.commute_study_api.ai.feedback;

/**
 * AI-06 통학 후 피드백. 호출 조건(답한 문제가 있을 때만, 세션당 1회)은 이 클라이언트를 쓰는 쪽(세션 종료 로직)의 책임이다.
 */
public interface FeedbackAiClient {

	FeedbackResponse feedback(FeedbackRequest request);
}
