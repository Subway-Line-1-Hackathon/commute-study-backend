package com.likelion.commute_study_api.ai.tutor;

import com.likelion.commute_study_api.ai.support.AiMeta;

/**
 * AI-05 스트림 이벤트 콜백. 호출하는 쪽(LN-06, #31)이 구현해서 자기 SseEmitter로 그대로 중계한다.
 * done을 받았을 때만 AI 질문 횟수를 차감해야 한다(9.10장) — 그 판단도 구현하는 쪽의 책임이다.
 */
public interface TutorEventListener {

	void onDelta(String text);

	void onDone(AiMeta meta);

	/** code는 AI 서버 원본 에러 코드(없을 수 있음), message는 사용자에게 보여줄 수 있는 문장. */
	void onError(String code, String message);
}
