package com.likelion.commute_study_api.session.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.session.dto.SessionSource;

/**
 * 세션 생성(슬라이스 12)에 필요한 SQL.
 * category·question·attempt·wrong_note는 다른 슬라이스 테이블이지만 읽기만 하고, 쓰는 것은 session·session_question뿐이다.
 * 모든 조회는 subject.user_id로 소유자를 거른다 — 다른 사용자의 단원·문제는 결과에 나오지 않는다.
 */
@Repository
public class SessionRepository {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public SessionRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/** 사용자가 고른 단원. questionStatus = PREPARING/AVAILABLE, materialStatus = READY/EXPIRED 등. */
	public record SessionCategory(long id, String name, String summary, String questionStatus, String materialStatus) {
		public boolean isPreparing() {
			return "PREPARING".equals(questionStatus);
		}
	}

	/**
	 * 세션 후보 문제와 이 사용자의 풀이 상태.
	 *
	 * @param solved      이 사용자가 한 번이라도 응답했는지(attempt 존재)
	 * @param wrongOpen   오답노트에 OPEN으로 있는지
	 * @param wrongListed OPEN이면서 오답노트 목록 상한(과목별 최근 wrongnote.max-open개) 안에 있는지
	 * @param lastWrongAt 마지막으로 틀린 시각(오답이 아니면 null)
	 */
	public record CandidateQuestion(
			long id,
			long categoryId,
			String type,
			String stem,
			List<String> choices,
			Integer sourcePage,
			String answer,
			String explanation,
			boolean solved,
			boolean wrongOpen,
			boolean wrongListed,
			Instant lastWrongAt
	) {
	}

	/** 요청한 단원 중 이 사용자 것만 돌려준다. 개수가 요청보다 적으면 없거나 남의 단원이 섞인 것이다. */
	public List<SessionCategory> findOwnedCategories(UUID userId, List<Long> categoryIds) {
		if (categoryIds.isEmpty()) {
			return List.of();
		}
		List<Object> args = new ArrayList<>();
		args.add(userId);
		args.addAll(categoryIds);
		return jdbcTemplate.query("""
				select c.id, c.name, c.summary,
				       c.question_status::text as question_status, m.status::text as material_status
				from public.category c
				join public.subject s on s.id = c.subject_id
				join public.material m on m.id = c.material_id
				where s.user_id = ? and c.id in (%s)
				order by c.material_id, c."order", c.id
				""".formatted(placeholders(categoryIds.size())),
				(rs, rowNum) -> new SessionCategory(
						rs.getLong("id"),
						rs.getString("name"),
						rs.getString("summary"),
						rs.getString("question_status"),
						rs.getString("material_status")),
				args.toArray());
	}

	/**
	 * 유형·난이도가 맞는 이 사용자의 문제를 풀이 상태와 함께 읽는다.
	 * categoryIds가 비어 있으면 이 사용자의 모든 단원이 대상이다(오답노트 다시 풀기).
	 * 정렬: 단원 순서 → 문제 id. 어떤 문제를 먼저 낼지는 서비스(선택 로직)가 정한다.
	 */
	public List<CandidateQuestion> findCandidates(UUID userId, List<Long> categoryIds, List<String> types,
			int difficulty, int maxListedWrongPerSubject) {
		// SQL의 ? 순서대로 넣는다.
		List<Object> args = new ArrayList<>();
		args.add(maxListedWrongPerSubject); // open_notes 목록 상한
		args.add(userId); // open_notes
		args.add(userId); // solved
		args.add(userId); // subject owner
		args.addAll(types);
		args.add(difficulty);
		String categoryFilter = "";
		if (!categoryIds.isEmpty()) {
			categoryFilter = "and q.category_id in (" + placeholders(categoryIds.size()) + ")";
			args.addAll(categoryIds);
		}

		return jdbcTemplate.query("""
				with open_notes as (
				    select wn.question_id, wn.last_wrong_at,
				           row_number() over (partition by c.subject_id order by wn.last_wrong_at desc, wn.question_id desc) <= ? as listed
				    from public.wrong_note wn
				    join public.question q on q.id = wn.question_id
				    join public.category c on c.id = q.category_id
				    where wn.user_id = ? and wn.status = 'OPEN'
				)
				select q.id, q.category_id, q.type::text as type, q.stem, q.choices::text as choices,
				       q.source_page, q.answer, q.explanation,
				       exists (select 1 from public.attempt a where a.user_id = ? and a.question_id = q.id) as solved,
				       o.question_id is not null as wrong_open,
				       coalesce(o.listed, false) as wrong_listed,
				       o.last_wrong_at
				from public.question q
				join public.category c on c.id = q.category_id
				join public.subject s on s.id = c.subject_id
				left join open_notes o on o.question_id = q.id
				where s.user_id = ?
				  and q.type::text in (%s)
				  and q.difficulty = ?
				  %s
				order by c.material_id, c."order", c.id, q.id
				""".formatted(placeholders(types.size()), categoryFilter),
				(rs, rowNum) -> {
					Timestamp lastWrongAt = rs.getTimestamp("last_wrong_at");
					return new CandidateQuestion(
							rs.getLong("id"),
							rs.getLong("category_id"),
							rs.getString("type"),
							rs.getString("stem"),
							fromJson(rs.getString("choices")),
							rs.getObject("source_page", Integer.class),
							rs.getString("answer"),
							rs.getString("explanation"),
							rs.getBoolean("solved"),
							rs.getBoolean("wrong_open"),
							rs.getBoolean("wrong_listed"),
							lastWrongAt == null ? null : lastWrongAt.toInstant());
				},
				args.toArray());
	}

	/**
	 * 출발하지 않은(READY) 이전 세션을 지운다(명세서 9.3 제안). session_question은 cascade로 함께 지워진다.
	 * 응답이 붙은 세션은 attempt FK 때문에 지울 수 없으므로 건드리지 않는다.
	 */
	public int deleteReadySessions(UUID userId) {
		return jdbcTemplate.update("""
				delete from public.session s
				where s.user_id = ? and s.status = 'READY'
				  and not exists (select 1 from public.attempt a where a.session_id = s.id)
				""", userId);
	}

	/** status는 기본값 READY. 선택한 단원과 세션 출처를 각각 저장한다. */
	public long insertSession(UUID userId, List<Long> categoryIds, List<String> questionTypes, int difficulty,
			int studyMin, String mode, SessionSource source) {
		String categoryArray = categoryIds.isEmpty() ? null
				: categoryIds.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
		Long id = jdbcTemplate.queryForObject("""
				insert into public.session (user_id, category_ids, question_types, difficulty, study_min, mode, source)
				values (?, ?::bigint[], ?::jsonb, ?, ?, ?::session_mode, ?)
				returning id
				""", Long.class, userId, categoryArray, toJson(questionTypes), difficulty, studyMin, mode, source.value());
		if (id == null) {
			throw new IllegalStateException("세션 id를 받지 못함");
		}
		return id;
	}

	/** 순서(order)는 1부터. */
	public void insertSessionQuestions(long sessionId, List<Long> questionIds) {
		List<Integer> indexes = new ArrayList<>();
		for (int i = 0; i < questionIds.size(); i++) {
			indexes.add(i);
		}
		jdbcTemplate.batchUpdate("""
				insert into public.session_question (session_id, question_id, "order")
				values (?, ?, ?)
				""", indexes, indexes.size(), (ps, i) -> {
			ps.setLong(1, sessionId);
			ps.setLong(2, questionIds.get(i));
			ps.setInt(3, i + 1);
		});
	}

	private static String placeholders(int count) {
		return String.join(", ", Collections.nCopies(count, "?"));
	}

	private List<String> fromJson(String json) {
		if (json == null) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, STRING_LIST);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("문제 선택지 JSON 읽기 실패", e);
		}
	}

	private String toJson(List<String> values) {
		try {
			return objectMapper.writeValueAsString(values);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("세션 유형 JSON 변환 실패", e);
		}
	}
}
