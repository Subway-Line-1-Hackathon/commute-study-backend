package com.likelion.commute_study_api.study;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StudyReadRepository {
    private final JdbcTemplate jdbc;
    public StudyReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<CategoryStats> categories(long subjectId, UUID userId) {
        // Aggregate attempts separately: repeated answers must not multiply the PREGEN denominator.
        return jdbc.query("""
                WITH owned_categories AS (
                    SELECT c.* FROM public.category c JOIN public.subject s ON s.id=c.subject_id
                    WHERE s.id=? AND s.user_id=?
                ), question_counts AS (
                    SELECT q.category_id, COUNT(*) AS total
                    FROM public.question q JOIN owned_categories c ON c.id=q.category_id
                    WHERE q.origin='PREGEN' GROUP BY q.category_id
                ), answer_counts AS (
                    SELECT q.category_id,
                        COUNT(DISTINCT CASE WHEN q.origin='PREGEN' AND a.correct IS NOT NULL THEN q.id END) AS solved,
                        COUNT(a.correct) AS graded,
                        SUM(CASE WHEN a.correct=true THEN 1 ELSE 0 END) AS correct_count,
                        MAX(a.answered_at) AS last_studied
                    FROM public.attempt a JOIN public.question q ON q.id=a.question_id
                    JOIN owned_categories c ON c.id=q.category_id
                    WHERE a.user_id=? GROUP BY q.category_id
                )
                SELECT c.id,c.name,c.page_start,c.page_end,c.question_status,
                    COALESCE(q.total,0) AS total,COALESCE(a.solved,0) AS solved,
                    COALESCE(a.graded,0) AS graded,COALESCE(a.correct_count,0) AS correct_count,a.last_studied
                FROM owned_categories c LEFT JOIN question_counts q ON q.category_id=c.id
                LEFT JOIN answer_counts a ON a.category_id=c.id ORDER BY c."order",c.id
                """, (rs,n) -> new CategoryStats(rs.getLong("id"),rs.getString("name"),
                rs.getInt("page_start"),rs.getInt("page_end"),rs.getString("question_status"),
                rs.getLong("total"),rs.getLong("solved"),rs.getLong("graded"),rs.getLong("correct_count"),
                rs.getObject("last_studied",OffsetDateTime.class)),subjectId,userId,userId);
    }

    public Explanation explanation(long questionId, UUID userId) {
        return jdbc.query("""
                SELECT q.explanation,q.source_page FROM public.question q
                JOIN public.category c ON c.id=q.category_id
                JOIN public.subject s ON s.id=c.subject_id
                WHERE q.id=? AND s.user_id=?
                """, (rs,n) -> new Explanation(rs.getString("explanation"),rs.getObject("source_page",Integer.class)),
                questionId,userId).stream().findFirst().orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
    }

    public record CategoryStats(long id,String name,int pageStart,int pageEnd,String questionStatus,
                                long total,long solved,long graded,long correct,OffsetDateTime lastStudiedAt) {}
    public record Explanation(String explanation,Integer sourcePage) {}
}
