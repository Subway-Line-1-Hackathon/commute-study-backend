package com.likelion.commute_study_api.session.dto;

import com.likelion.commute_study_api.session.SessionStatus;

/** POST /api/sessions/{id}/end 응답. 이미 끝난 세션이면 그때의 상태(COMPLETED/ENDED)를 그대로 준다. */
public record EndSessionResponse(SessionStatus status) {
}
