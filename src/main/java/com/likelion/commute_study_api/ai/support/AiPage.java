package com.likelion.commute_study_api.ai.support;

/**
 * AI 서버 공통 타입 Page (API 명세 4장). AI-01 응답과 AI-02·AI-05 요청에서 그대로 재사용한다.
 */
public record AiPage(int page, String text) {
}
