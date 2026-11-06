package com.likelion.commute_study_api.ai.tutor;

import java.util.List;

import com.likelion.commute_study_api.ai.support.AiPage;

/**
 * AI-05 요청 (AI 서버 API 명세 9장). history는 최대 6개(최근 3왕복)만 보낸다.
 */
public record TutorAskRequest(Category category, Question question, List<HistoryItem> history, String message) {

	public record Category(Long categoryId, String name, List<AiPage> pages) {
	}

	public record Question(String stem, List<String> choices, String answer, String explanation, String userAnswer) {
	}

	public record HistoryItem(String role, String content) {
	}
}
