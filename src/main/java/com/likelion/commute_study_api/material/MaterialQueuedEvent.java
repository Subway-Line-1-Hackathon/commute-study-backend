package com.likelion.commute_study_api.material;

/** Internal dispatch only. Retry has already atomically claimed PROCESSING. */
public record MaterialQueuedEvent(long materialId, boolean retry) {}

