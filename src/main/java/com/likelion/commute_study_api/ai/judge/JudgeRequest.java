package com.likelion.commute_study_api.ai.judge;

import java.util.List;

/**
 * AI-03 요청 (AI 서버 API 명세 7장). 필드 이름은 AI 서버 스키마와 1:1로 맞춘다.
 */
public record JudgeRequest(String stem, List<String> acceptedAnswers, String explanation, String userAnswer) {
}
