package com.likelion.commute_study_api.ai.judge;

/**
 * AI-03 응답 (AI 서버 API 명세 7장).
 */
public record JudgeResponse(boolean correct, String reason) {
}
