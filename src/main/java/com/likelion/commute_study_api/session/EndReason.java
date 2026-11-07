package com.likelion.commute_study_api.session;

/**
 * 세션이 ENDED가 된 이유. DB 컬럼은 없고 SessionFinishedEvent에만 담긴다(#30 피드백 호출 여부 판단용).
 * QUIT·ARRIVED·NEW_START는 POST /api/sessions/{id}/end로 받고, NEXT_DAY는 서버만 쓴다.
 */
public enum EndReason {

	/** 중간 종료(그만하기). S5 */
	QUIT,
	/** 도착. S5 */
	ARRIVED,
	/** 이어하기 팝업에서 "새로 시작", 또는 다른 세션을 시작해 기존 IN_PROGRESS가 끝남. S6 */
	NEW_START,
	/** 시작 다음 날(KST 자정)이 지나 GET /api/sessions/current에서 자동 종료. S7 */
	NEXT_DAY;

	/** 클라이언트가 /end 요청으로 보낼 수 있는 값인지. */
	public boolean isClientReason() {
		return this != NEXT_DAY;
	}
}