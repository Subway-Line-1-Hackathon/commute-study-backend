package com.likelion.commute_study_api.session.dto;

import com.likelion.commute_study_api.session.EndReason;

import jakarta.validation.constraints.NotNull;

/** POST /api/sessions/{id}/end 요청. reason은 QUIT·ARRIVED·NEW_START 중 하나(NEXT_DAY는 서버 전용이라 400). */
public record EndSessionRequest(@NotNull EndReason reason) {
}
