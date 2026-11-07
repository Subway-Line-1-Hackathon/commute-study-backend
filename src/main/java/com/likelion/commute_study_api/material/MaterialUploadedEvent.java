package com.likelion.commute_study_api.material;

import java.util.UUID;

/** Committed upload hook for slice 10 retention. No deletion policy is implemented here. */
public record MaterialUploadedEvent(long materialId, UUID userId) {}

