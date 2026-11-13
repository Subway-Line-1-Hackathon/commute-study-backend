package com.likelion.commute_study_api.question.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.GeneratedQuestion;
import com.likelion.commute_study_api.ai.support.AiPage;

/**
 * 문제 생성(사전·추가)에 필요한 SQL. category·material·material_page는 다른 슬라이스 담당 테이블이지만
 * 그쪽 Repository를 고치지 않으려고 여기서 직접 읽고, category.question_status 변경도 여기서 한다.
 */
@Repository
public class QuestionGenerationRepository {

	private static final String CATEGORY_COLUMNS = """
			c.id, c.material_id, c.name, c.page_start, c.page_end,
			c.question_status::text as question_status, m.status::text as material_status
			""";

	private static final RowMapper<CategoryTarget> CATEGORY_MAPPER = (rs, rowNum) -> new CategoryTarget(
			rs.getLong("id"),
			rs.getLong("material_id"),
			rs.getString("name"),
			rs.getInt("page_start"),
			rs.getInt("page_end"),
			rs.getString("question_status"),
			rs.getString("material_status")
	);

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public QuestionGenerationRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public record CategoryTarget(
			long id,
			long materialId,
			String name,
			int pageStart,
			int pageEnd,
			String questionStatus,
			String materialStatus
	) {
		public boolean isPreparing() {
			return "PREPARING".equals(questionStatus);
		}

		public boolean isPageInRange(int page) {
			return page >= pageStart && page <= pageEnd;
		}
	}

	public Optional<CategoryTarget> findCategory(long categoryId) {
		List<CategoryTarget> rows = jdbcTemplate.query("""
				select %s
				from public.category c
				join public.material m on m.id = c.material_id
				where c.id = ? and m.deleted_at is null
				""".formatted(CATEGORY_COLUMNS), CATEGORY_MAPPER, categoryId);
		return rows.stream().findFirst();
	}

	public List<CategoryTarget> findPreparingCategoriesByMaterial(long materialId) {
		return jdbcTemplate.query("""
				select %s
				from public.category c
				join public.material m on m.id = c.material_id
				where c.material_id = ? and c.question_status = 'PREPARING' and m.deleted_at is null
				order by c."order"
				""".formatted(CATEGORY_COLUMNS), CATEGORY_MAPPER, materialId);
	}

	/** 재시도 스케줄러용: 처리가 끝난(READY) 자료의 아직 준비 중인 단원 전부. */
	public List<CategoryTarget> findPreparingCategoriesOfReadyMaterials() {
		return jdbcTemplate.query("""
				select %s
				from public.category c
				join public.material m on m.id = c.material_id
				where c.question_status = 'PREPARING' and m.status = 'READY' and m.deleted_at is null
				order by c.id
				""".formatted(CATEGORY_COLUMNS), CATEGORY_MAPPER);
	}

	public List<AiPage> findPages(long materialId, int pageStart, int pageEnd) {
		return jdbcTemplate.query("""
				select page, text
				from public.material_page
				where material_id = ? and page between ? and ?
				order by page
				""", (rs, rowNum) -> new AiPage(rs.getInt("page"), rs.getString("text")),
				materialId, pageStart, pageEnd);
	}

	/** Called inside the writer transaction to serialize result insertion with material deletion. */
	public boolean lockActiveMaterialForCategory(long categoryId) {
		return !jdbcTemplate.queryForList("""
				select m.id from public.material m
				where m.id=(select c.material_id from public.category c where c.id=?)
				  and m.deleted_at is null for update
				""", Long.class, categoryId).isEmpty();
	}

	/** AI-02 excludeStems용. AI 서버는 최근 60개만 보므로 그만큼만 읽는다. */
	public List<String> findRecentStems(long categoryId, int limit) {
		return jdbcTemplate.queryForList("""
				select stem
				from public.question
				where category_id = ?
				order by id desc
				limit ?
				""", String.class, categoryId, limit);
	}

	public int countQuestions(long categoryId, String type, int difficulty) {
		Integer count = jdbcTemplate.queryForObject("""
				select count(*)
				from public.question
				where category_id = ? and type = ?::question_type and difficulty = ?
				""", Integer.class, categoryId, type, difficulty);
		return count == null ? 0 : count;
	}

	public void insertQuestions(long categoryId, String origin, List<GeneratedQuestion> questions) {
		jdbcTemplate.batchUpdate("""
				insert into public.question
				    (category_id, type, difficulty, stem, choices, answer, accepted_answers,
				     rubric, explanation, source_page, concept_tags, origin)
				values (?, ?::question_type, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::question_origin)
				""", questions, questions.size(), (ps, q) -> {
			ps.setLong(1, categoryId);
			ps.setString(2, q.type());
			ps.setInt(3, q.difficulty());
			ps.setString(4, q.stem());
			ps.setString(5, toJson(q.choices()));
			ps.setString(6, q.answer());
			ps.setString(7, toJson(q.acceptedAnswers()));
			// rubric 컬럼은 아직 text다. 논술형만 값이 있어 빈 목록이면 null로 둔다.
			ps.setString(8, q.rubric() == null || q.rubric().isEmpty() ? null : toJson(q.rubric()));
			ps.setString(9, q.explanation());
			ps.setInt(10, q.sourcePage());
			ps.setString(11, toJson(q.conceptTags()));
			ps.setString(12, origin);
		});
	}

	/** PREPARING일 때만 바꾼다. 이미 AVAILABLE이면 0을 돌려준다. */
	public int markAvailable(long categoryId) {
		return jdbcTemplate.update("""
				update public.category
				set question_status = 'AVAILABLE'
				where id = ? and question_status = 'PREPARING'
				  and exists (select 1 from public.material m where m.id=category.material_id and m.deleted_at is null)
				""", categoryId);
	}

	private String toJson(List<String> values) {
		try {
			return objectMapper.writeValueAsString(values == null ? List.of() : values);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("문제 저장용 JSON 변환 실패", e);
		}
	}
}
