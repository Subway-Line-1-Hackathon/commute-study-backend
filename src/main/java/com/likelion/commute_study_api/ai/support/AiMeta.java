package com.likelion.commute_study_api.ai.support;

/**
 * AI-01·AI-02 응답과 AI-05 done 이벤트에 공통으로 들어 있는 사용량 정보(AI 서버 API 명세 4장).
 * 저장하지 않고 소요 시간만 로그에 남긴다.
 */
public record AiMeta(String model, int inputTokens, int outputTokens, long elapsedMs, int attempts) {
}
