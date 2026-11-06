package com.likelion.commute_study_api.ai.feedback;

import java.util.List;

/**
 * AI-06 요청 (AI 서버 API 명세 10장).
 */
public record FeedbackRequest(
		Long sessionId,
		String mode,
		int total,
		int answered,
		int correct,
		List<String> correctConcepts,
		List<WrongItem> wrongItems
) {

	public record WrongItem(String stem, List<String> conceptTags) {
	}
}
