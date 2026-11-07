package com.likelion.commute_study_api.answer.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 단답 판정(슬라이스 14)에 필요한 문제 조회. 읽기만 한다(판정 결과 저장은 POST /api/attempts).
 * subject.user_id로 소유자를 거른다 — 다른 사용자의 문제는 결과에 나오지 않는다.
 */
@Repository
public class AnswerJudgeRepository {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public AnswerJudgeRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * AI-03 요청에 필요한 문제 정보.
	 *
	 * @param type            OX/MC/SHORT
	 * @param answer          대표 정답
	 * @param acceptedAnswers 인정 정답 목록(없으면 빈 목록)
	 */
	public record JudgeQuestion(long id, String type, String stem, String answer, List<String> acceptedAnswers,
			String explanation) {
	}

	/** 이 사용자의 문제를 읽는다. 없거나 남의 문제면 빈 값. */
	public Optional<JudgeQuestion> findOwnedQuestion(UUID userId, long questionId) {
		return jdbcTemplate.query("""
				select q.id, q.type::text as type, q.stem, q.answer, q.accepted_answers::text as accepted_answers,
				       q.explanation
				from public.question q
				join public.category c on c.id = q.category_id
				join public.subject s on s.id = c.subject_id
				where q.id = ? and s.user_id = ?
				""", (rs, rowNum) -> new JudgeQuestion(
				rs.getLong("id"),
				rs.getString("type"),
				rs.getString("stem"),
				rs.getString("answer"),
				fromJson(rs.getString("accepted_answers")),
				rs.getString("explanation")), questionId, userId).stream().findFirst();
	}

	private List<String> fromJson(String json) {
		if (json == null) {
			return List.of();
		}
		try {
			List<String> values = objectMapper.readValue(json, STRING_LIST);
			return values == null ? List.of() : values;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("정답 목록 JSON 읽기 실패", e);
		}
	}
}
