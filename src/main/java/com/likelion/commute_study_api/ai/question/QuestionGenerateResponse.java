package com.likelion.commute_study_api.ai.question;

import java.util.List;

import com.likelion.commute_study_api.ai.support.AiMeta;

/**
 * AI-02 응답 (AI 서버 API 명세 6장). shortfall이 비어있지 않으면 부족분이고, questions가 0개면
 * AiHttpSupport가 애초에 502 LLM_FAILED로 예외를 던진다(AI 서버가 200 대신 502를 준다).
 */
public record QuestionGenerateResponse(
		List<GeneratedQuestion> questions,
		List<Shortfall> shortfall,
		Rejected rejected,
		AiMeta meta
) {

	public record GeneratedQuestion(
			String type,
			int difficulty,
			String stem,
			List<String> choices,
			String answer,
			List<String> acceptedAnswers,
			List<String> rubric,
			String explanation,
			int sourcePage,
			String evidenceQuote,
			List<String> conceptTags
	) {
	}

	public record Shortfall(String type, int difficulty, int count) {
	}

	public record Rejected(int schema, int evidence, int duplicate) {
	}
}
