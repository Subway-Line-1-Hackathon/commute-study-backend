package com.likelion.commute_study_api.material;

/** Emitted inside the transaction that transitions to READY or FAILED. */
public record MaterialSettledEvent(long materialId) {}
