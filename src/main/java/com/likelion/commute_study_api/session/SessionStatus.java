package com.likelion.commute_study_api.session;

/**
 * 학습 세션 상태(session.status). 백엔드 개발 명세서 6장 상태 전이 S1~S7.
 * COMPLETED·ENDED는 다시 열지 않는다.
 */
public enum SessionStatus {

	READY,
	IN_PROGRESS,
	COMPLETED,
	ENDED;

	public boolean isFinished() {
		return this == COMPLETED || this == ENDED;
	}
}