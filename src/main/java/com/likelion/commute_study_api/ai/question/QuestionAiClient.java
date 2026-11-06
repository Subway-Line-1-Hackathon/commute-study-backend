package com.likelion.commute_study_api.ai.question;

import java.util.concurrent.CompletableFuture;

/**
 * AI-02 문제·해설 생성. 단원 사전 생성(count 합 40)과 커리큘럼 생성 때의 부족분 요청 모두 이 클라이언트를 쓴다.
 * 서버 시간 상한 55초·백엔드 타임아웃 60초는 사전 생성·부족분이 같다(v0.3).
 */
public interface QuestionAiClient {

	CompletableFuture<QuestionGenerateResponse> generate(QuestionGenerateRequest request);
}
