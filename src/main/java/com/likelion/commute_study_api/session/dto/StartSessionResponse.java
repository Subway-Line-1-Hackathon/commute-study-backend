package com.likelion.commute_study_api.session.dto;

import java.time.Instant;

import com.likelion.commute_study_api.session.SessionStatus;

/** POST /api/sessions/{id}/start 응답. startedAt은 ISO-8601(UTC). */
public record StartSessionResponse(SessionStatus status, Instant startedAt) {
}
