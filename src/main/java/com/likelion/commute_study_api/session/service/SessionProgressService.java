package com.likelion.commute_study_api.session.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.util.KstClock;
import com.likelion.commute_study_api.session.EndReason;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.CurrentSessionResponse;
import com.likelion.commute_study_api.session.dto.EndSessionResponse;
import com.likelion.commute_study_api.session.dto.StartSessionResponse;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.Progress;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.SessionRow;

/**
 * 세션 시작·종료·이어하기(명세서 6장 S2·S5·S6·S7, 9.5·9.6장).
 * 세션이 ENDED가 되면 SessionFinishedEvent를 발행한다. 트랜잭션 안에서 발행하므로
 * 받는 쪽(#30 피드백)은 @TransactionalEventListener(AFTER_COMMIT)로 커밋된 뒤에 처리해야 한다.
 */
@Service
public class SessionProgressService {

	private static final Logger log = LoggerFactory.getLogger(SessionProgressService.class);

	private final CurrentUser currentUser;
	private final SessionProgressRepository repository;
	private final ApplicationEventPublisher events;
	private final ZoneId zoneId;
	private final Clock clock;

	@Autowired
	public SessionProgressService(CurrentUser currentUser, SessionProgressRepository repository,
			ApplicationEventPublisher events, KstClock kstClock) {
		this(currentUser, repository, events, kstClock, Clock.systemUTC());
	}

	SessionProgressService(CurrentUser currentUser, SessionProgressRepository repository,
			ApplicationEventPublisher events, KstClock kstClock, Clock clock) {
		this.currentUser = currentUser;
		this.repository = repository;
		this.events = events;
		this.zoneId = kstClock.getZoneId();
		this.clock = clock;
	}

	/**
	 * 출발: READY → IN_PROGRESS. 이미 IN_PROGRESS인 다른 세션이 있으면 먼저 ENDED(NEW_START)로 끝낸다.
	 * READY가 아니면 409. 동시에 두 세션을 시작해 부분 유니크 인덱스에 걸려도 409.
	 */
	@Transactional
	public StartSessionResponse start(Jwt jwt, long sessionId) {
		UUID userId = currentUser.ensureUser(jwt);
		SessionRow session = findOwned(userId, sessionId);
		if (session.status() != SessionStatus.READY) {
			throw conflict("READY 세션만 시작할 수 있습니다.", session.status());
		}

		Instant now = clock.instant();
		repository.lockInProgressSession(userId)
				.ifPresent(previous -> end(previous, EndReason.NEW_START, now));
		try {
			repository.markStarted(sessionId, now);
		} catch (DuplicateKeyException e) {
			// 다른 요청이 그 사이에 다른 세션을 시작함(사용자당 IN_PROGRESS 1개)
			throw conflict("이미 진행 중인 세션이 있습니다.", SessionStatus.IN_PROGRESS);
		}
		log.info("[세션] 시작 sessionId={}", sessionId);
		return new StartSessionResponse(SessionStatus.IN_PROGRESS, now);
	}

	/**
	 * 중간 종료·도착·새로 시작: IN_PROGRESS → ENDED.
	 * 이미 끝난 세션이면 그 상태를 그대로 돌려준다(재전송 안전, 이벤트 다시 안 보냄). 출발 전(READY)이면 409.
	 */
	@Transactional
	public EndSessionResponse end(Jwt jwt, long sessionId, EndReason reason) {
		if (!reason.isClientReason()) {
			throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "종료 사유가 올바르지 않습니다.",
					Map.of("reason", "QUIT, ARRIVED, NEW_START 중 하나"));
		}
		UUID userId = currentUser.ensureUser(jwt);
		SessionRow session = findOwned(userId, sessionId);
		if (session.status().isFinished()) {
			return new EndSessionResponse(session.status());
		}
		if (session.status() == SessionStatus.READY) {
			throw conflict("출발하지 않은 세션은 종료할 수 없습니다.", session.status());
		}
		end(session, reason, clock.instant());
		return new EndSessionResponse(SessionStatus.ENDED);
	}

	/**
	 * 이어하기용 진행 중 세션. 없으면 빈 값(204).
	 * 시작한 날(KST)이 오늘보다 이전이면 ENDED(NEXT_DAY)로 바꾸고 빈 값을 준다(S7, 지연 처리).
	 */
	@Transactional
	public Optional<CurrentSessionResponse> current(Jwt jwt) {
		UUID userId = currentUser.ensureUser(jwt);
		Optional<SessionRow> found = repository.lockInProgressSession(userId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		SessionRow session = found.get();
		Instant now = clock.instant();
		if (startedBeforeToday(session, now)) {
			end(session, EndReason.NEXT_DAY, now);
			return Optional.empty();
		}
		Progress progress = repository.countProgress(session.id());
		return Optional.of(new CurrentSessionResponse(session.id(), repository.findCategoryNames(session.id()),
				progress.answered(), progress.total()));
	}

	private boolean startedBeforeToday(SessionRow session, Instant now) {
		if (session.startedAt() == null) {
			return false;
		}
		LocalDate startedDay = session.startedAt().atZone(zoneId).toLocalDate();
		return startedDay.isBefore(now.atZone(zoneId).toLocalDate());
	}

	/** 잠근 IN_PROGRESS 세션을 ENDED로 바꾸고 이벤트를 발행한다. */
	private void end(SessionRow session, EndReason reason, Instant now) {
		if (repository.markEnded(session.id(), now)) {
			events.publishEvent(new SessionFinishedEvent(session.id(), session.userId(), SessionStatus.ENDED.name(),
					reason.name()));
			log.info("[세션] 종료 sessionId={} reason={}", session.id(), reason);
		}
	}

	private SessionRow findOwned(UUID userId, long sessionId) {
		return repository.lockOwnedSession(userId, sessionId)
				.orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND, "세션을 찾을 수 없습니다."));
	}

	private static ApiException conflict(String message, SessionStatus status) {
		return new ApiException(CommonErrorCode.STATE_CONFLICT, message, Map.of("status", status.name()));
	}
}
