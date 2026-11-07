package com.likelion.commute_study_api.session.dto;

import com.likelion.commute_study_api.session.SessionStatus;

/**
 * POST /api/attempts 응답.
 *
 * @param saved         이번 요청에서 새로 저장된 응답 수(이미 있던 응답은 세지 않음)
 * @param progress      세션 전체 진행(answered = 저장된 응답 수, total = 세션 문제 수)
 * @param sessionStatus 저장 후 세션 상태. 마지막 문제까지 응답하면 COMPLETED
 */
public record SaveAttemptsResponse(int saved, Progress progress, SessionStatus sessionStatus) {

	public record Progress(int answered, int total) {
	}
}
