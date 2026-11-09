package com.likelion.commute_study_api.session.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.likelion.commute_study_api.session.SessionStatus;

/**
 * 세션 진행(시작·종료·이어하기·완료, 슬라이스 13)에 필요한 SQL.
 * 상태를 바꾸기 전에 세션 행을 잠가(for update) 시작·종료·답안 저장이 동시에 와도 순서대로 처리한다.
 * 상태 변경 update는 모두 "현재 상태" 조건을 걸어, 조건이 안 맞으면 0행이 바뀐다.
 */
@Repository
public class SessionProgressRepository {

	private static final String SESSION_COLUMNS = """
			s.id, s.user_id, s.status::text as status, s.source = 'wrongNotes' as wrong_notes,
			s.started_at, s.ended_at
			""";

	private static final RowMapper<SessionRow> SESSION_ROW = (rs, rowNum) -> new SessionRow(
			rs.getLong("id"),
			rs.getObject("user_id", UUID.class),
			SessionStatus.valueOf(rs.getString("status")),
			rs.getBoolean("wrong_notes"),
			toInstant(rs.getTimestamp("started_at")),
			toInstant(rs.getTimestamp("ended_at")));

	private final JdbcTemplate jdbcTemplate;

	public SessionProgressRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * @param wrongNotes 오답노트 다시 풀기로 만든 세션인지(source가 wrongNotes). 정답이면 오답을 RESOLVED로 바꾸는 기준
	 */
	public record SessionRow(long id, UUID userId, SessionStatus status, boolean wrongNotes, Instant startedAt,
			Instant endedAt) {
	}

	/** answered = 저장된 응답 수, total = 세션 문제 수. */
	public record Progress(int answered, int total) {
		public boolean isAllAnswered() {
			return total > 0 && answered >= total;
		}
	}

	/** 이 사용자의 세션을 잠그고 읽는다. 없거나 남의 세션이면 빈 값. 트랜잭션 안에서 불러야 잠금이 유지된다. */
	public Optional<SessionRow> lockOwnedSession(UUID userId, long sessionId) {
		return jdbcTemplate.query("select " + SESSION_COLUMNS + """
				from public.session s
				where s.id = ? and s.user_id = ?
				for update
				""", SESSION_ROW, sessionId, userId).stream().findFirst();
	}

	/** 이 사용자의 IN_PROGRESS 세션을 잠그고 읽는다. 부분 유니크 인덱스 때문에 최대 1개다. */
	public Optional<SessionRow> lockInProgressSession(UUID userId) {
		return jdbcTemplate.query("select " + SESSION_COLUMNS + """
				from public.session s
				where s.user_id = ? and s.status = 'IN_PROGRESS'
				for update
				""", SESSION_ROW, userId).stream().findFirst();
	}

	/** READY → IN_PROGRESS. 바뀌었으면 true. */
	public boolean markStarted(long sessionId, Instant startedAt) {
		return jdbcTemplate.update("""
				update public.session
				set status = 'IN_PROGRESS', started_at = ?
				where id = ? and status = 'READY'
				""", Timestamp.from(startedAt), sessionId) == 1;
	}

	/** IN_PROGRESS → ENDED. 바뀌었으면 true. */
	public boolean markEnded(long sessionId, Instant endedAt) {
		return updateFinished(sessionId, SessionStatus.ENDED, endedAt);
	}

	/** IN_PROGRESS → COMPLETED. 바뀌었으면 true. */
	public boolean markCompleted(long sessionId, Instant endedAt) {
		return updateFinished(sessionId, SessionStatus.COMPLETED, endedAt);
	}

	/** 저장된 응답 수와 세션 문제 수. */
	public Progress countProgress(long sessionId) {
		return jdbcTemplate.queryForObject("""
				select (select count(*) from public.attempt a where a.session_id = ?) as answered,
				       (select count(*) from public.session_question sq where sq.session_id = ?) as total
				""", (rs, rowNum) -> new Progress(rs.getInt("answered"), rs.getInt("total")), sessionId, sessionId);
	}

	/**
	 * 세션 문제들이 속한 단원 이름을 세션 문제 순서대로(중복 없이) 돌려준다.
	 * 선택된 문제에 해당하는 단원을 session_question에서 구한다.
	 */
	public List<String> findCategoryNames(long sessionId) {
		return jdbcTemplate.queryForList("""
				select c.name
				from public.session_question sq
				join public.question q on q.id = sq.question_id
				join public.category c on c.id = q.category_id
				where sq.session_id = ?
				group by c.id, c.name
				order by min(sq."order")
				""", String.class, sessionId);
	}

	private boolean updateFinished(long sessionId, SessionStatus status, Instant endedAt) {
		return jdbcTemplate.update("""
				update public.session
				set status = ?::session_status, ended_at = ?
				where id = ? and status = 'IN_PROGRESS'
				""", status.name(), Timestamp.from(endedAt), sessionId) == 1;
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}