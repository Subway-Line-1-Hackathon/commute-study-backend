package com.likelion.commute_study_api.ai.judge;

/**
 * AI-03 단답형 판정. 호출 시점 판단(정규화 비교 후 불일치할 때만)은 이 클라이언트를 쓰는 쪽(LN-03)의 책임이다.
 */
public interface JudgeAiClient {

	JudgeResponse judge(JudgeRequest request);
}