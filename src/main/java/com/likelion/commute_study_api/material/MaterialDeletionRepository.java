package com.likelion.commute_study_api.material;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MaterialDeletionRepository {
    private final JdbcTemplate jdbc;
    public MaterialDeletionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<DeletionTarget> lockOwned(long id, UUID userId) {
        return jdbc.query("""
                SELECT status, deleted_at FROM public.material
                WHERE id=? AND subject_id IN (SELECT id FROM public.subject WHERE user_id=?)
                FOR UPDATE
                """, (rs,n) -> new DeletionTarget(rs.getString("status"), rs.getTimestamp("deleted_at") != null),
                id, userId).stream().findFirst();
    }
    public void markDeleted(long id) {
        jdbc.update("UPDATE public.material SET deleted_at=CURRENT_TIMESTAMP, cleanup_pending=false WHERE id=?", id);
    }
    public void enqueue(long materialId, List<String> references) {
        for (String reference : references) {
            jdbc.update("INSERT INTO public.material_delete_object(material_id,object_ref) VALUES (?,?)", materialId, reference);
        }
    }
    public List<PendingObject> pending(int limit) {
        return jdbc.query("""
                SELECT id, object_ref FROM public.material_delete_object
                ORDER BY retry_order_at, id LIMIT ?
                """, (rs,n) -> new PendingObject(rs.getLong("id"), rs.getString("object_ref")), limit);
    }
    public void completed(long id) { jdbc.update("DELETE FROM public.material_delete_object WHERE id=?", id); }
    public void failed(long id) { jdbc.update("UPDATE public.material_delete_object SET retry_order_at=CURRENT_TIMESTAMP WHERE id=?", id); }

    public record DeletionTarget(String status, boolean deleted) {}
    public record PendingObject(long id, String reference) {}
}
