package com.likelion.commute_study_api.feedback.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.feedback.FeedbackStatus;

/**
 * 통학 후 피드백(슬라이스 15, AI-06) 입력 조회와 결과 저장.
 * 채점 대기(attempt.correct가 null) 답안은 answered·correct·개념·틀린 문제 어디에도 넣지 않는다(AI 명세 8장).
 */
@Repository
public class SessionFeedbackRepository {

	/** AI 서버가 wrongItems의 앞 20개만 쓰므로 그보다 많이 보내지 않는다(AI 명세 10장). */
	public static final int MAX_WRONG_ITEMS = 20;

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public SessionFeedbackRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * AI-06 요청에 필요한 세션 정보.
	 *
	 * @param mode            CURRICULUM / REVIEW
	 * @param status          세션 상태(COMPLETED/ENDED일 때만 호출한다)
	 * @param feedback        이미 저장된 피드백(없으면 null)
	 * @param total           세션의 문제 수
	 * @param answered        채점된 답 수(채점 대기 제외)
	 * @param correct         정답 수(자가 판정 포함)
	 * @param correctConcepts 맞힌 문제들의 개념 태그(문제 순서대로, 중복 포함 — 상위 개념 집계는 AI 서버가 한다)
	 * @param wrongItems      틀린 문제(문제 순서대로 최대 20개)
	 */
	public record FeedbackSource(long sessionId, String mode, String status, String feedback, FeedbackStatus feedbackStatus, int total,
			int answered, int correct, List<String> correctConcepts, List<WrongQuestion> wrongItems) {
	}

	public record WrongQuestion(String stem, List<String> conceptTags) {
	}

	/** 세션이 없으면 빈 값. */
	public Optional<FeedbackSource> findSource(long sessionId) {
		record Summary(String mode, String status, String feedback, FeedbackStatus feedbackStatus, int total, int answered, int correct) {
		}
		Optional<Summary> summary = jdbcTemplate.query("""
				select s.mode::text as mode, s.status::text as status, s.feedback, s.feedback_status,
				       (select count(*) from public.session_question sq where sq.session_id = s.id) as total,
				       (select count(*) from public.attempt a
				        where a.session_id = s.id and a.correct is not null) as answered,
				       (select count(*) from public.attempt a
				        where a.session_id = s.id and a.correct = true) as correct
				from public.session s
				where s.id = ?
				""", (rs, rowNum) -> new Summary(
				rs.getString("mode"),
				rs.getString("status"),
				rs.getString("feedback"),
				FeedbackStatus.valueOf(rs.getString("feedback_status")),
				rs.getInt("total"),
				rs.getInt("answered"),
				rs.getInt("correct")), sessionId).stream().findFirst();
		if (summary.isEmpty()) {
			return Optional.empty();
		}
		Summary s = summary.get();
		return Optional.of(new FeedbackSource(sessionId, s.mode(), s.status(), s.feedback(), s.feedbackStatus(), s.total(), s.answered(),
				s.correct(), findCorrectConcepts(sessionId), findWrongItems(sessionId)));
	}

	private List<String> findCorrectConcepts(long sessionId) {
		return jdbcTemplate.query("""
				select q.concept_tags::text as concept_tags
				from public.attempt a
				join public.question q on q.id = a.question_id
				left join public.session_question sq on sq.session_id = a.session_id and sq.question_id = a.question_id
				where a.session_id = ? and a.correct = true
				order by sq."order" nulls last, a.id
				""", (rs, rowNum) -> fromJson(rs.getString("concept_tags")), sessionId)
				.stream().flatMap(List::stream).toList();
	}

	private List<WrongQuestion> findWrongItems(long sessionId) {
		return jdbcTemplate.query("""
				select q.stem, q.concept_tags::text as concept_tags
				from public.attempt a
				join public.question q on q.id = a.question_id
				left join public.session_question sq on sq.session_id = a.session_id and sq.question_id = a.question_id
				where a.session_id = ? and a.correct = false
				order by sq."order" nulls last, a.id
				limit ?
				""", (rs, rowNum) -> new WrongQuestion(rs.getString("stem"), fromJson(rs.getString("concept_tags"))),
				sessionId, MAX_WRONG_ITEMS);
	}

	/** BEFORE_COMMIT에서 호출: 종료 사유와 무응답 생략을 원래 종료 트랜잭션에 저장한다. */
	public void prepare(long sessionId, String reason) {
		boolean skipReason = "NEW_START".equals(reason) || "NEXT_DAY".equals(reason);
		jdbcTemplate.update("""
				update public.session set feedback_status = 'SKIPPED', feedback_status_reason = ?
				where id = ? and status in ('COMPLETED', 'ENDED')
				  and feedback_status = 'PENDING' and feedback is null
				  and feedback_status_reason is null
				  and (? or not exists (select 1 from public.attempt a
				                        where a.session_id = public.session.id and a.correct is not null))
				""", skipReason ? reason : "NO_GRADED_ANSWERS", sessionId, skipReason);
	}

	/** 원자적 claim: 중복 이벤트가 동일한 AI 요청을 다시 시작하지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean claimPending(long sessionId) {
		return jdbcTemplate.update("""
				update public.session set feedback_status_reason = 'GENERATING'
				where id = ? and status in ('COMPLETED', 'ENDED')
				  and feedback_status = 'PENDING' and feedback is null and feedback_status_reason is null
				""", sessionId) == 1;
	}

	/** 피드백과 READY를 같은 UPDATE로 저장한다. 늦게 도착한 작업은 terminal 상태를 덮지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean saveIfAbsent(long sessionId, String feedback) {
		return jdbcTemplate.update("""
				update public.session set feedback = ?, feedback_status = 'READY', feedback_status_reason = null
				where id = ? and feedback is null and feedback_status = 'PENDING'
				  and status in ('COMPLETED', 'ENDED')
				""", feedback, sessionId) == 1;
	}

	/** 동기 AFTER_COMMIT 콜백에서도 원래 연결을 재사용하지 않고 별도 트랜잭션으로 확정한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markTerminal(long sessionId, FeedbackStatus status, String reason) {
		if (status != FeedbackStatus.SKIPPED && status != FeedbackStatus.FAILED) {
			throw new IllegalArgumentException("종료 상태는 SKIPPED 또는 FAILED여야 합니다.");
		}
		return jdbcTemplate.update("""
				update public.session set feedback_status = ?, feedback_status_reason = ?
				where id = ? and feedback_status = 'PENDING' and feedback is null
				  and status in ('COMPLETED', 'ENDED')
				""", status.name(), reason, sessionId) == 1;
	}

	/** 중복 제출 실패는 이미 실행을 시작한 작업의 상태를 바꾸지 않는다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean failDispatch(long sessionId) {
		return jdbcTemplate.update("""
				update public.session set feedback_status = 'FAILED', feedback_status_reason = 'DISPATCH_REJECTED'
				where id = ? and feedback_status = 'PENDING' and feedback is null
				  and feedback_status_reason is null and status in ('COMPLETED', 'ENDED')
				""", sessionId) == 1;
	}

	private List<String> fromJson(String json) {
		if (json == null) {
			return List.of();
		}
		try {
			List<String> values = objectMapper.readValue(json, STRING_LIST);
			return values == null ? List.of() : values;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("개념 태그 JSON 읽기 실패", e);
		}
	}
}
