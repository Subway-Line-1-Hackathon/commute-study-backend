package com.likelion.commute_study_api.subject;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SubjectRepository {
    private final JdbcTemplate jdbc;

    public SubjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long create(UUID userId, String name, LocalDate examDate) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO public.subject (user_id, name, exam_date) VALUES (?, ?, ?)", new String[] {"id"});
            statement.setObject(1, userId);
            statement.setString(2, name);
            if (examDate == null) statement.setNull(3, Types.DATE);
            else statement.setDate(3, Date.valueOf(examDate));
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey(), "Database did not return subject id").longValue();
    }

    public List<Subject> findAllByUserId(UUID userId) {
        return jdbc.query("""
                SELECT id, name, exam_date FROM public.subject
                WHERE user_id = ? ORDER BY id ASC
                """, (rs, row) -> new Subject(rs.getLong("id"), rs.getString("name"),
                rs.getObject("exam_date", LocalDate.class)), userId);
    }

    public boolean isOwnedBy(long subjectId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.subject WHERE id = ? AND user_id = ?)
                """, Boolean.class, subjectId, userId));
    }

    /** Missing and foreign subjects deliberately have the same error contract. */
    public void requireOwned(long subjectId, UUID userId) {
        if (!isOwnedBy(subjectId, userId)) throw new ApiException(CommonErrorCode.NOT_FOUND);
    }

    public record Subject(long id, String name, LocalDate examDate) {}
}

