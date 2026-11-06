package com.likelion.commute_study_api.ai.question;

import java.util.List;

import com.likelion.commute_study_api.ai.support.AiPage;

/**
 * AI-02 요청 (AI 서버 API 명세 6장). requests[]의 count 합계는 40 이하여야 한다(넘으면 AI 서버가 400).
 * excludeStems는 같은 단원 question.stem 중 최근 60개만 보내도 된다.
 */
public record QuestionGenerateRequest(Category category, List<TypeRequest> requests, List<String> excludeStems) {

	public record Category(Long categoryId, String name, List<AiPage> pages) {
	}

	public record TypeRequest(String type, int difficulty, int count) {
	}
}
