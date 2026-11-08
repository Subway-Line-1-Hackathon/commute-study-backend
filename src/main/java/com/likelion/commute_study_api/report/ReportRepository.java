package com.likelion.commute_study_api.report;

import java.time.*;
import java.util.*;
import com.likelion.commute_study_api.common.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {
    private final JdbcTemplate jdbc;
    public ReportRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    // EXISTS avoids multiplying an attempt by other questions in the same session.
    private static final String ATTEMPTS="""
            SELECT a.correct,a.answered_at,c.id AS category_id,a.session_id
            FROM public.attempt a JOIN public.question q ON q.id=a.question_id
            JOIN public.category c ON c.id=q.category_id
            JOIN public.session s ON s.id=a.session_id
            WHERE c.subject_id=? AND a.user_id=? AND s.user_id=?
              AND EXISTS (SELECT 1 FROM public.session_question sq
                          WHERE sq.session_id=a.session_id AND sq.question_id=a.question_id)
            """;

    public SessionInfo session(long subjectId,long sessionId,UUID user) {
        return jdbc.query("""
                SELECT s.started_at,s.feedback FROM public.session s
                WHERE s.id=? AND s.user_id=? AND EXISTS (
                    SELECT 1 FROM public.session_question sq JOIN public.question q ON q.id=sq.question_id
                    JOIN public.category c ON c.id=q.category_id
                    WHERE sq.session_id=s.id AND c.subject_id=?)
                """,(rs,n)->new SessionInfo(rs.getObject("started_at",OffsetDateTime.class),rs.getString("feedback")),
                sessionId,user,subjectId).stream().findFirst().orElseThrow(()->new ApiException(CommonErrorCode.NOT_FOUND));
    }

    public SessionStats sessionStats(long subjectId,long sessionId,UUID user) {
        return jdbc.queryForObject("WITH answers AS ("+ATTEMPTS+"""
                ) SELECT COUNT(*) AS solved,COUNT(correct) AS graded,
                    COALESCE(SUM(CASE WHEN correct=true THEN 1 ELSE 0 END),0) AS correct_count,
                    COALESCE(SUM(CASE WHEN correct=false THEN 1 ELSE 0 END),0) AS wrong,
                    MAX(answered_at) AS last_answer FROM answers WHERE session_id=?
                """,(rs,n)->new SessionStats(rs.getLong("solved"),rs.getLong("graded"),rs.getLong("correct_count"),
                rs.getLong("wrong"),rs.getObject("last_answer",OffsetDateTime.class)),subjectId,user,user,sessionId);
    }

    public List<DayStats> trend(long subjectId,UUID user,int minimum) {
        return jdbc.query("WITH answers AS ("+ATTEMPTS+"""
                ), localized AS (
                    SELECT answered_at AT TIME ZONE 'Asia/Seoul' AS kst_time,correct FROM answers
                ), dated AS (
                    SELECT EXTRACT(YEAR FROM kst_time) AS study_year,EXTRACT(MONTH FROM kst_time) AS study_month,
                        EXTRACT(DAY FROM kst_time) AS study_day,correct FROM localized
                ) SELECT study_year,study_month,study_day,COUNT(*) AS answers,COUNT(correct) AS graded,
                    SUM(CASE WHEN correct=true THEN 1 ELSE 0 END) AS correct_count
                  FROM dated GROUP BY study_year,study_month,study_day HAVING COUNT(*)>=?
                  ORDER BY study_year,study_month,study_day
                """,(rs,n)->new DayStats(LocalDate.of(rs.getInt("study_year"),rs.getInt("study_month"),rs.getInt("study_day")),rs.getLong("answers"),
                rs.getLong("graded"),rs.getLong("correct_count")),subjectId,user,user,minimum);
    }

    public List<CategoryStats> categories(long subjectId,UUID user) {
        return jdbc.query("WITH answers AS ("+ATTEMPTS+"""
                ), totals AS (
                    SELECT category_id,COUNT(correct) AS graded,
                        SUM(CASE WHEN correct=true THEN 1 ELSE 0 END) AS correct_count
                    FROM answers GROUP BY category_id
                ) SELECT c.id,COALESCE(t.graded,0) AS graded,COALESCE(t.correct_count,0) AS correct_count
                  FROM public.category c LEFT JOIN totals t ON t.category_id=c.id
                  WHERE c.subject_id=? ORDER BY c.id
                """,(rs,n)->new CategoryStats(rs.getLong("id"),rs.getLong("graded"),rs.getLong("correct_count")),
                subjectId,user,user,subjectId);
    }
    public record SessionInfo(OffsetDateTime startedAt,String feedback) {}
    public record SessionStats(long solved,long graded,long correct,long wrong,OffsetDateTime lastAnswer) {}
    public record DayStats(LocalDate date,long answers,long graded,long correct) {}
    public record CategoryStats(long id,long graded,long correct) {}
}
