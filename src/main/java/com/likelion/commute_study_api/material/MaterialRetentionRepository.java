package com.likelion.commute_study_api.material;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MaterialRetentionRepository {
    private final JdbcTemplate jdbc;
    public MaterialRetentionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<UUID> usersWithRetainedMaterials() {
        return jdbc.queryForList("""
                SELECT DISTINCT s.user_id FROM public.material m JOIN public.subject s ON s.id=m.subject_id
                WHERE m.status IN ('READY','FAILED')
                """, UUID.class);
    }

    public Optional<UUID> owner(long id) {
        return jdbc.queryForList("""
                SELECT s.user_id FROM public.material m JOIN public.subject s ON s.id=m.subject_id WHERE m.id=?
                """, UUID.class, id).stream().findFirst();
    }

    public List<Candidate> candidates(UUID userId) {
        return jdbc.query("""
                SELECT m.id, m.created_at, m.expires_at, m.cleanup_pending
                FROM public.material m JOIN public.subject s ON s.id=m.subject_id
                WHERE s.user_id=? AND m.status IN ('READY','FAILED') ORDER BY m.created_at ASC, m.id ASC
                """, (rs, n) -> new Candidate(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getBoolean("cleanup_pending")), userId);
    }

    /** Durable intent blocks retry, including after a partial Storage deletion or process restart. */
    public boolean reserve(long id, UUID userId) {
        return jdbc.update("""
                UPDATE public.material SET cleanup_pending=true
                WHERE id=? AND status IN ('READY','FAILED')
                  AND subject_id IN (SELECT id FROM public.subject WHERE user_id=?)
                """, id, userId) == 1;
    }

    public boolean finish(long id, UUID userId) {
        int updated = jdbc.update("""
                UPDATE public.material SET status='EXPIRED', file_url='', cleanup_pending=false,
                    processing_started_at=NULL, processing_token=NULL
                WHERE id=? AND cleanup_pending=true AND status IN ('READY','FAILED')
                  AND subject_id IN (SELECT id FROM public.subject WHERE user_id=?)
                """, id, userId);
        if (updated == 1) jdbc.update("UPDATE public.material_file SET file_url='' WHERE material_id=?", id);
        return updated == 1;
    }

    public List<Long> staleProcessing(Instant cutoff) {
        return jdbc.queryForList("""
                SELECT id FROM public.material WHERE status='PROCESSING'
                AND processing_started_at <= ? AND cleanup_pending=false
                """, Long.class, Timestamp.from(cutoff));
    }

    public boolean recover(long id, Instant cutoff) {
        return jdbc.update("""
                UPDATE public.material SET status='FAILED', error_message=?,
                    processing_started_at=NULL, processing_token=NULL
                WHERE id=? AND status='PROCESSING' AND processing_started_at <= ? AND cleanup_pending=false
                """, "처리 시간이 초과되었습니다. 다시 시도해주세요.", id, Timestamp.from(cutoff)) == 1;
    }

    public record Candidate(long id, Instant createdAt, Instant expiresAt, boolean pending) {}
}
