package com.likelion.commute_study_api.ai.support;

/**
 * AI 서버 에러 응답 {"error":{"code","message"}} (AI 서버 API 명세 3장) 그대로.
 */
public record AiErrorBody(AiError error) {

	public record AiError(String code, String message) {
	}
}
