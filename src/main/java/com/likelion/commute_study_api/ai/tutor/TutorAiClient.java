package com.likelion.commute_study_api.ai.tutor;

/**
 * AI-05 AI 질문(SSE). ask()는 스트림이 끝날 때까지 호출한 스레드를 블로킹한다 —
 * 호출하는 쪽(#31)이 자기 스레드/비동기 컨텍스트에서 실행해야 한다.
 */
public interface TutorAiClient {

	void ask(TutorAskRequest request, TutorEventListener listener);
}
