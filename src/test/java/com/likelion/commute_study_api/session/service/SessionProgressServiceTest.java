package com.likelion.commute_study_api.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.AppProperties;
import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.exception.ErrorCode;
import com.likelion.commute_study_api.common.util.KstClock;
import com.likelion.commute_study_api.session.EndReason;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.CurrentSessionResponse;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository;
import com.likelion.commute_study_api.user.UserRepository;

class SessionProgressServiceTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final Jwt JWT = Jwt.withTokenValue("t").header("alg", "none").subject(USER.toString()).build();
	/** KST 2026-11-08 10:00 */
	private static final Instant NOW = Instant.parse("2026-11-08T01:00:00Z");

	private final FakeProgressRepository repository = new FakeProgressRepository();
	private final List<Object> events = new ArrayList<>();

	private SessionProgressService service(Instant now) {
		UserRepository users = new UserRepository(null) {
			@Override
			public void createIfAbsent(UUID userId) {
			}
		};
		return new SessionProgressService(new CurrentUser(users), repository, events::add,
				new KstClock(new AppProperties()), Clock.fixed(now, ZoneOffset.UTC));
	}

	private static void assertError(ErrorCode expected, Runnable action) {
		ApiException e = assertThrows(ApiException.class, action::run);
		assertEquals(expected, e.getErrorCode());
	}

	// ---- 시작 ----

	@Test
	void READY_세션을_시작하면_IN_PROGRESS와_시작_시각() {
		repository.add(1, USER, SessionStatus.READY, null);

		var response = service(NOW).start(JWT, 1);

		assertEquals(SessionStatus.IN_PROGRESS, response.status());
		assertEquals(NOW, response.startedAt());
		assertEquals(SessionStatus.IN_PROGRESS, repository.get(1).status());
		assertTrue(events.isEmpty());
	}

	@Test
	void 진행_중인_세션이_있으면_NEW_START로_끝내고_시작() {
		repository.add(1, USER, SessionStatus.IN_PROGRESS, NOW.minusSeconds(600));
		repository.add(2, USER, SessionStatus.READY, null);

		service(NOW).start(JWT, 2);

		assertEquals(SessionStatus.ENDED, repository.get(1).status());
		assertEquals(NOW, repository.get(1).endedAt());
		assertEquals(SessionStatus.IN_PROGRESS, repository.get(2).status());
		assertEquals(List.of(new SessionFinishedEvent(1L, USER, "ENDED", "NEW_START")), events);
	}

	@Test
	void READY가_아니면_시작은_409() {
		repository.add(1, USER, SessionStatus.COMPLETED, NOW);
		assertError(CommonErrorCode.STATE_CONFLICT, () -> service(NOW).start(JWT, 1));
	}

	@Test
	void 남의_세션이나_없는_세션은_404() {
		repository.add(1, OTHER, SessionStatus.READY, null);
		assertError(CommonErrorCode.NOT_FOUND, () -> service(NOW).start(JWT, 1));
		assertError(CommonErrorCode.NOT_FOUND, () -> service(NOW).start(JWT, 99));
		assertError(CommonErrorCode.NOT_FOUND, () -> service(NOW).end(JWT, 1, EndReason.QUIT));
	}

	@Test
	void 동시_시작으로_유니크_인덱스에_걸리면_409() {
		repository.add(1, USER, SessionStatus.READY, null);
		repository.duplicateOnStart = true;
		assertError(CommonErrorCode.STATE_CONFLICT, () -> service(NOW).start(JWT, 1));
	}

	// ---- 종료 ----

	@Test
	void 진행_중인_세션을_끝내면_ENDED와_이벤트() {
		repository.add(1, USER, SessionStatus.IN_PROGRESS, NOW.minusSeconds(600));

		var response = service(NOW).end(JWT, 1, EndReason.ARRIVED);

		assertEquals(SessionStatus.ENDED, response.status());
		assertEquals(SessionStatus.ENDED, repository.get(1).status());
		assertEquals(List.of(new SessionFinishedEvent(1L, USER, "ENDED", "ARRIVED")), events);
	}

	@Test
	void 이미_끝난_세션을_다시_끝내면_그_상태를_주고_이벤트는_없다() {
		repository.add(1, USER, SessionStatus.COMPLETED, NOW.minusSeconds(600));

		var response = service(NOW).end(JWT, 1, EndReason.QUIT);

		assertEquals(SessionStatus.COMPLETED, response.status());
		assertTrue(events.isEmpty());
	}

	@Test
	void 출발_전_세션은_종료_409() {
		repository.add(1, USER, SessionStatus.READY, null);
		assertError(CommonErrorCode.STATE_CONFLICT, () -> service(NOW).end(JWT, 1, EndReason.QUIT));
	}

	@Test
	void NEXT_DAY는_클라이언트가_보낼_수_없다() {
		repository.add(1, USER, SessionStatus.IN_PROGRESS, NOW);
		assertError(CommonErrorCode.VALIDATION_ERROR, () -> service(NOW).end(JWT, 1, EndReason.NEXT_DAY));
		assertEquals(SessionStatus.IN_PROGRESS, repository.get(1).status());
	}

	// ---- 이어하기 ----

	@Test
	void 진행_중인_세션이_없으면_빈_값() {
		repository.add(1, USER, SessionStatus.ENDED, NOW.minusSeconds(600));
		assertTrue(service(NOW).current(JWT).isEmpty());
	}

	@Test
	void 오늘_시작한_세션은_진행_상황을_준다() {
		// KST 2026-11-08 00:00에 시작, 같은 날 23:59에 조회
		Instant startedAt = Instant.parse("2026-11-07T15:00:00Z");
		repository.add(1, USER, SessionStatus.IN_PROGRESS, startedAt);
		repository.progress = new SessionProgressRepository.Progress(3, 10);

		Optional<CurrentSessionResponse> current = service(Instant.parse("2026-11-08T14:59:00Z")).current(JWT);

		assertEquals(new CurrentSessionResponse(1, List.of("스택", "큐"), 3, 10), current.orElseThrow());
		assertEquals(SessionStatus.IN_PROGRESS, repository.get(1).status());
	}

	@Test
	void KST_자정이_지나면_ENDED로_바꾸고_빈_값() {
		// KST 2026-11-07 23:59에 시작, 1분 뒤(11-08 00:00) 조회
		Instant startedAt = Instant.parse("2026-11-07T14:59:00Z");
		Instant now = Instant.parse("2026-11-07T15:00:00Z");
		repository.add(1, USER, SessionStatus.IN_PROGRESS, startedAt);

		assertTrue(service(now).current(JWT).isEmpty());
		assertEquals(SessionStatus.ENDED, repository.get(1).status());
		assertEquals(now, repository.get(1).endedAt());
		assertEquals(List.of(new SessionFinishedEvent(1L, USER, "ENDED", "NEXT_DAY")), events);
	}

	/** 메모리 Map으로 SQL의 상태 조건(where status = ...)을 흉내 낸다. */
	static class FakeProgressRepository extends SessionProgressRepository {
		final Map<Long, SessionRow> sessions = new LinkedHashMap<>();
		Progress progress = new Progress(0, 0);
		boolean duplicateOnStart;

		FakeProgressRepository() {
			super(null);
		}

		void add(long id, UUID userId, SessionStatus status, Instant startedAt) {
			sessions.put(id, new SessionRow(id, userId, status, false, startedAt, null));
		}

		SessionRow get(long id) {
			return sessions.get(id);
		}

		@Override
		public Optional<SessionRow> lockOwnedSession(UUID userId, long sessionId) {
			return Optional.ofNullable(sessions.get(sessionId)).filter(s -> s.userId().equals(userId));
		}

		@Override
		public Optional<SessionRow> lockInProgressSession(UUID userId) {
			return sessions.values().stream()
					.filter(s -> s.userId().equals(userId) && s.status() == SessionStatus.IN_PROGRESS)
					.findFirst();
		}

		@Override
		public boolean markStarted(long sessionId, Instant startedAt) {
			if (duplicateOnStart) {
				throw new DuplicateKeyException("session_user_in_progress_uk");
			}
			return change(sessionId, SessionStatus.READY, SessionStatus.IN_PROGRESS, startedAt, null);
		}

		@Override
		public boolean markEnded(long sessionId, Instant endedAt) {
			return change(sessionId, SessionStatus.IN_PROGRESS, SessionStatus.ENDED, null, endedAt);
		}

		@Override
		public boolean markCompleted(long sessionId, Instant endedAt) {
			return change(sessionId, SessionStatus.IN_PROGRESS, SessionStatus.COMPLETED, null, endedAt);
		}

		@Override
		public Progress countProgress(long sessionId) {
			return progress;
		}

		@Override
		public List<String> findCategoryNames(long sessionId) {
			return List.of("스택", "큐");
		}

		private boolean change(long id, SessionStatus from, SessionStatus to, Instant startedAt, Instant endedAt) {
			SessionRow s = sessions.get(id);
			if (s == null || s.status() != from) {
				return false;
			}
			sessions.put(id, new SessionRow(id, s.userId(), to, s.wrongNotes(),
					startedAt != null ? startedAt : s.startedAt(), endedAt != null ? endedAt : s.endedAt()));
			return true;
		}
	}
}
