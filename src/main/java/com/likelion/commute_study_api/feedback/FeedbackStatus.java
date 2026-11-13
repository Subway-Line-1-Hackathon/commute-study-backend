package com.likelion.commute_study_api.feedback;

/** Persisted AI feedback lifecycle, independent of whether feedback text is null. */
public enum FeedbackStatus {
	PENDING,
	READY,
	SKIPPED,
	FAILED
}
