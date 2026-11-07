package com.likelion.commute_study_api.question.service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 사전 생성 실패 횟수를 서버 메모리에 기록한다(DB에 FAILED 상태가 없어서, 팀 결정).
 * 서버가 재시작되면 비워지고, 그때 PREPARING 단원은 처음부터 다시 시도된다.
 */
@Component
public class PregenFailureTracker {

	public record Failure(int count, Instant lastFailedAt) {
	}

	private final Map<Long, Failure> failures = new ConcurrentHashMap<>();

	public void recordFailure(long categoryId, Instant now) {
		failures.merge(categoryId, new Failure(1, now),
				(old, ignored) -> new Failure(old.count() + 1, now));
	}

	public void clear(long categoryId) {
		failures.remove(categoryId);
	}

	public Optional<Failure> get(long categoryId) {
		return Optional.ofNullable(failures.get(categoryId));
	}
}
