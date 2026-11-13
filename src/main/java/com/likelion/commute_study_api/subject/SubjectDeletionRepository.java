package com.likelion.commute_study_api.subject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SubjectDeletionRepository {
    private final JdbcTemplate jdbc;

    public SubjectDeletionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Boolean> lockOwned(long id, UUID userId) {
        return jdbc.query("""
                SELECT deleted_at FROM public.subject WHERE id=? AND user_id=? FOR UPDATE
                """, (rs, row) -> rs.getTimestamp("deleted_at") != null, id, userId).stream().findFirst();
    }

    public List<Long> lockMaterials(long subjectId) {
        return jdbc.queryForList("""
                SELECT id FROM public.material WHERE subject_id=? ORDER BY id FOR UPDATE
                """, Long.class, subjectId);
    }

    public void markDeleted(long id) {
        jdbc.update("UPDATE public.subject SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", id);
    }
}
