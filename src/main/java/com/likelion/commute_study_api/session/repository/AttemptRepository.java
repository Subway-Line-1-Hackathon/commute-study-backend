package com.likelion.commute_study_api.session.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 답안 저장(슬라이스 13)에 필요한 SQL. attempt·wrong_note에 쓴다.
 * 세션 소유자 확인과 잠금은 서비스가 SessionProgressRepository.lockOwnedSession으로 먼저 한다.
 */
@Repository
public class AttemptRepository {

	private final JdbcTemplate jdbcTemplate;

	public AttemptRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 채점에 필요한 세션 문제 정보. type = OX/MC/SHORT, answer = 대표 정답. */
	public record SessionQuestion(long id, String type, String answer) {
	}

	/** 저장할 응답 한 건. correct가 null이면 채점 대기(단답 판정 실패 등). */
	public record NewAttempt(long questionId, String answer, Boolean correct, boolean selfJudged, Instant answeredAt) {
	}

	/** 요청한 문제 중 이 세션에 들어 있는 것만 돌려준다. 개수가 적으면 세션에 없는 문제가 섞인 것이다. */
	public List<SessionQuestion> findSessionQuestions(long sessionId, List<Long> questionIds) {
		if (questionIds.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		args.add(sessionId);
		args.addAll(questionIds);
		return jdbcTemplate.query("""
				select q.id, q.type::text as type, q.answer
				from public.session_question sq
				join public.question q on q.id = sq.question_id
				where sq.session_id = ? and q.id in (%s)
				""".formatted(String.join(", ", Collections.nCopies(questionIds.size(), "?"))),
				(rs, rowNum) -> new SessionQuestion(rs.getLong("id"), rs.getString("type"), rs.getString("answer")),
				args.toArray());
	}

	/**
	 * 응답을 저장한다. (session_id, question_id)가 이미 있으면 건너뛴다(중복 전송 안전).
	 *
	 * @return 요청과 같은 순서로, 새로 저장됐으면 true
	 */
	public List<Boolean> insertIfAbsent(UUID userId, long sessionId, List<NewAttempt> attempts) {
		if (attempts.isEmpty()) {
			return List.of();
		}
		int[] counts = jdbcTemplate.batchUpdate("""
				insert into public.attempt (user_id, question_id, session_id, answer, correct, self_judged, answered_at)
				values (?, ?, ?, ?, ?, ?, ?)
				on conflict (session_id, question_id) do nothing
				""", attempts, attempts.size(), (ps, attempt) -> {
			ps.setObject(1, userId);
			ps.setLong(2, attempt.questionId());
			ps.setLong(3, sessionId);
			ps.setString(4, attempt.answer());
			if (attempt.correct() == null) {
				ps.setNull(5, Types.BOOLEAN);
			} else {
				ps.setBoolean(5, attempt.correct());
			}
			ps.setBoolean(6, attempt.selfJudged());
			ps.setTimestamp(7, Timestamp.from(attempt.answeredAt()));
		})[0];
		List<Boolean> inserted = new ArrayList<>(counts.length);
		for (int count : counts) {
			inserted.add(count == 1);
		}
		return inserted;
	}

	/**
	 * 오답 등록(W1·W2·W4): 없으면 OPEN·1회로 만들고, 있으면 wrong_count + 1, OPEN으로 되돌린다.
	 * last_wrong_at은 더 최근 시각을 남긴다(오프라인 응답이 늦게 도착해도 거꾸로 가지 않게).
	 */
	public void recordWrong(UUID userId, long questionId, Instant wrongAt) {
		jdbcTemplate.update("""
				insert into public.wrong_note (user_id, question_id, wrong_count, status, last_wrong_at)
				values (?, ?, 1, 'OPEN', ?)
				on conflict (user_id, question_id) do update
				set wrong_count = public.wrong_note.wrong_count + 1,
				    status = 'OPEN',
				    last_wrong_at = greatest(public.wrong_note.last_wrong_at, excluded.last_wrong_at)
				""", userId, questionId, Timestamp.from(wrongAt));
	}

	/** 오답 해결(W3): OPEN이면 RESOLVED로 바꾼다. 목록에서 지우지 않는다. 바뀌었으면 true. */
	public boolean resolveWrong(UUID userId, long questionId) {
		return jdbcTemplate.update("""
				update public.wrong_note
				set status = 'RESOLVED'
				where user_id = ? and question_id = ? and status = 'OPEN'
				""", userId, questionId) == 1;
	}
}
