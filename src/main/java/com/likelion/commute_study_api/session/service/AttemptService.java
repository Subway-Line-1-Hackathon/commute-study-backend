package com.likelion.commute_study_api.session.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest;
import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest.AttemptItem;
import com.likelion.commute_study_api.session.dto.SaveAttemptsResponse;
import com.likelion.commute_study_api.session.repository.AttemptRepository;
import com.likelion.commute_study_api.session.repository.AttemptRepository.NewAttempt;
import com.likelion.commute_study_api.session.repository.AttemptRepository.SessionQuestion;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.Progress;
import com.likelion.commute_study_api.session.repository.SessionProgressRepository.SessionRow;

/**
 * 답안 일괄 저장(명세서 9.5장). 한 트랜잭션에서 세션 행을 잠그고 처리한다.
 *
 * <ul>
 * <li>(sessionId, questionId)가 이미 있으면 건너뛴다. 같은 묶음 안의 중복은 첫 번째만 쓴다</li>
 * <li>O/X·객관식은 서버가 정답과 비교해 correct를 정한다. 단답은 앱이 보낸 correct(판정 API 결과·자가 판정)를 쓴다</li>
 * <li>새로 저장된 응답만 오답노트에 반영한다: 오답이면 등록·증가(OPEN), 오답노트 다시 풀기 세션에서 정답이면 RESOLVED</li>
 * <li>IN_PROGRESS 세션의 모든 문제에 응답이 있으면 COMPLETED로 바꾸고 SessionFinishedEvent를 발행한다</li>
 * </ul>
 * 오프라인 응답이 늦게 도착할 수 있어 ENDED·COMPLETED 세션에도 저장한다. 출발 전(READY)만 409.
 */
@Service
public class AttemptService {

	private static final Logger log = LoggerFactory.getLogger(AttemptService.class);
	private static final Set<String> SERVER_GRADED = Set.of("OX", "MC");

	private final CurrentUser currentUser;
	private final SessionProgressRepository sessionRepository;
	private final AttemptRepository attemptRepository;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	@Autowired
	public AttemptService(CurrentUser currentUser, SessionProgressRepository sessionRepository,
			AttemptRepository attemptRepository, ApplicationEventPublisher events) {
		this(currentUser, sessionRepository, attemptRepository, events, Clock.systemUTC());
	}

	AttemptService(CurrentUser currentUser, SessionProgressRepository sessionRepository,
			AttemptRepository attemptRepository, ApplicationEventPublisher events, Clock clock) {
		this.currentUser = currentUser;
		this.sessionRepository = sessionRepository;
		this.attemptRepository = attemptRepository;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public SaveAttemptsResponse save(Jwt jwt, SaveAttemptsRequest request) {
		UUID userId = currentUser.ensureUser(jwt);
		SessionRow session = sessionRepository.lockOwnedSession(userId, request.sessionId())
				.orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND, "세션을 찾을 수 없습니다."));
		if (session.status() == SessionStatus.READY) {
			throw new ApiException(CommonErrorCode.STATE_CONFLICT, "출발하지 않은 세션에는 답안을 저장할 수 없습니다.",
					Map.of("status", session.status().name()));
		}

		Map<Long, AttemptItem> items = new LinkedHashMap<>();
		for (AttemptItem item : request.attempts()) {
			items.putIfAbsent(item.questionId(), item);
		}
		Map<Long, SessionQuestion> questions = attemptRepository
				.findSessionQuestions(session.id(), List.copyOf(items.keySet())).stream()
				.collect(Collectors.toMap(SessionQuestion::id, Function.identity()));
		List<Long> missing = items.keySet().stream().filter(id -> !questions.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			throw new ApiException(CommonErrorCode.NOT_FOUND, "세션에 없는 문제가 포함되어 있습니다.",
					Map.of("questionIds", missing));
		}

		Instant now = clock.instant();
		List<NewAttempt> attempts = new ArrayList<>();
		for (AttemptItem item : items.values()) {
			attempts.add(grade(item, questions.get(item.questionId()), now));
		}
		List<Boolean> inserted = attemptRepository.insertIfAbsent(userId, session.id(), attempts);

		int saved = 0;
		for (int i = 0; i < attempts.size(); i++) {
			if (!inserted.get(i)) {
				continue; // 이미 저장된 응답: 오답노트도 이미 반영됨
			}
			saved++;
			NewAttempt attempt = attempts.get(i);
			if (Boolean.FALSE.equals(attempt.correct())) {
				attemptRepository.recordWrong(userId, attempt.questionId(), attempt.answeredAt());
			} else if (Boolean.TRUE.equals(attempt.correct()) && session.wrongNotes()) {
				attemptRepository.resolveWrong(userId, attempt.questionId());
			}
		}

		Progress progress = sessionRepository.countProgress(session.id());
		SessionStatus status = session.status();
		if (status == SessionStatus.IN_PROGRESS && progress.isAllAnswered()
				&& sessionRepository.markCompleted(session.id(), now)) {
			status = SessionStatus.COMPLETED;
			events.publishEvent(new SessionFinishedEvent(session.id(), userId, status.name(), null));
			log.info("[세션] 완료 sessionId={}", session.id());
		}
		log.info("[답안] 저장 sessionId={} saved={} skipped={} progress={}/{}", session.id(), saved,
				attempts.size() - saved, progress.answered(), progress.total());
		return new SaveAttemptsResponse(saved, new SaveAttemptsResponse.Progress(progress.answered(), progress.total()),
				status);
	}

	/**
	 * 저장할 응답을 만든다. O/X·객관식은 서버 채점, 단답은 앱 판정을 그대로 쓴다.
	 * answeredAt이 서버 시각보다 미래면(기기 시계 오차) 서버 시각으로 맞춘다.
	 */
	private static NewAttempt grade(AttemptItem item, SessionQuestion question, Instant now) {
		Instant answeredAt = item.answeredAt().isAfter(now) ? now : item.answeredAt();
		if (SERVER_GRADED.contains(question.type()) && question.answer() != null) {
			boolean correct = normalize(question.type(), item.answer()).equals(normalize(question.type(), question.answer()));
			return new NewAttempt(question.id(), item.answer(), correct, false, answeredAt);
		}
		return new NewAttempt(question.id(), item.answer(), item.correct(), item.selfJudgedOrFalse(), answeredAt);
	}

	/**
	 * 비교용 정규화: 앞뒤 공백 제거, 대문자. O/X는 예전 시드 데이터의 true/false도 O/X로 본다.
	 * 객관식 정답은 선택지 번호("1"~"4")다(AI 서버 ↔ 백엔드 API 명세 "AI 서버가 보장하는 값").
	 */
	static String normalize(String type, String value) {
		String v = value.strip().toUpperCase(Locale.ROOT);
		if ("OX".equals(type)) {
			if (v.equals("TRUE")) {
				return "O";
			}
			if (v.equals("FALSE")) {
				return "X";
			}
		}
		return v;
	}
}
