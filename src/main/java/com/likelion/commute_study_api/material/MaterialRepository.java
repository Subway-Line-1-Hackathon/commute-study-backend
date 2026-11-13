package com.likelion.commute_study_api.material;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.likelion.commute_study_api.ai.material.MaterialProcessResponse;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class MaterialRepository {
    private final JdbcTemplate jdbc;
    public MaterialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long insert(long subjectId, String reference, String name, String type, String kind, long size, Instant expires) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO public.material
                    (subject_id, file_url, file_name, file_type, kind, size, status, expires_at)
                    VALUES (?, ?, ?, CAST(? AS material_file_type), CAST(? AS material_kind), ?, 'UPLOADED', ?)
                    """, new String[] {"id"});
            ps.setLong(1, subjectId); ps.setString(2, reference); ps.setString(3, name);
            ps.setString(4, type); ps.setString(5, kind); ps.setLong(6, size);
            ps.setTimestamp(7, Timestamp.from(expires));
            return ps;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public List<MaterialSummary> listByUser(UUID userId) {
        return jdbc.query("""
                SELECT m.id, m.subject_id, m.file_name, m.status, m.created_at
                FROM public.material m JOIN public.subject s ON s.id = m.subject_id
                WHERE s.user_id = ? AND m.deleted_at IS NULL ORDER BY m.created_at DESC
                """, (rs, n) -> new MaterialSummary(rs.getLong("id"), rs.getLong("subject_id"),
                rs.getString("file_name"), rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class)),
                userId);
    }

    public Material owned(long id, UUID userId) {
        return jdbc.query("""
                SELECT m.* FROM public.material m JOIN public.subject s ON s.id = m.subject_id
                WHERE m.id = ? AND s.user_id = ? AND m.deleted_at IS NULL
                """, this::map, id, userId).stream().findFirst().orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
    }

    public void insertFiles(long materialId, java.util.List<String> references) {
        for (int i = 0; i < references.size(); i++) {
            jdbc.update("INSERT INTO public.material_file(material_id, page_order, file_url) VALUES (?, ?, ?)",
                    materialId, i + 1, references.get(i));
        }
    }

    /** Also reusable by slice 10: image (PNG·JPG) deletion must remove all objects, not just material.file_url. */
    public java.util.List<String> fileReferences(Material material) {
        if (!MaterialUploadValidator.isImage(material.type())) return java.util.List.of(material.reference());
        var references = jdbc.queryForList(
                "SELECT file_url FROM public.material_file WHERE material_id = ? ORDER BY page_order ASC",
                String.class, material.id());
        if (references.isEmpty() || references.size() > 20) throw new IllegalStateException("Invalid image file references");
        return references;
    }

    /** Capture every persisted original reference, including legacy/mixed image rows. */
    public List<String> deletionReferences(Material material) {
        var references = new java.util.LinkedHashSet<String>();
        if (material.reference() != null && !material.reference().isBlank()) references.add(material.reference());
        for (String ref : jdbc.queryForList("SELECT file_url FROM public.material_file WHERE material_id=?", String.class, material.id())) {
            if (ref != null && !ref.isBlank()) references.add(ref);
        }
        return List.copyOf(references);
    }

    /**
     * 회원 탈퇴(#67)용: 이 사용자의 모든 자료 원본 경로(PDF·PPTX는 material.file_url, PNG는 material_file 전체).
     * 이미 정리된 자료(EXPIRED)는 경로가 빈 문자열이라 뺀다. 상태와 관계없이 모두 돌려준다(처리 중 자료 포함).
     */
    public java.util.List<String> fileReferencesOfUser(UUID userId) {
        return jdbc.queryForList("""
                SELECT m.file_url FROM public.material m JOIN public.subject s ON s.id = m.subject_id
                WHERE s.user_id = ? AND m.file_url <> ''
                UNION
                SELECT f.file_url FROM public.material_file f
                JOIN public.material m ON m.id = f.material_id JOIN public.subject s ON s.id = m.subject_id
                WHERE s.user_id = ? AND f.file_url <> ''
                ORDER BY 1
                """, String.class, userId, userId);
    }

    public Material get(long id) {
        return jdbc.queryForObject("SELECT * FROM public.material WHERE id = ?", this::map, id);
    }

    public boolean claim(long id, String expected, UUID token) {
        return jdbc.update("""
                UPDATE public.material SET status = 'PROCESSING', error_message = NULL,
                    processing_started_at = CURRENT_TIMESTAMP, processing_token = ?
                WHERE id = ? AND status = CAST(? AS material_status) AND expires_at > CURRENT_TIMESTAMP AND cleanup_pending = false AND deleted_at IS NULL
                """, token, id, expected) == 1;
    }

    public UUID processingToken(long id) {
        return jdbc.queryForObject("SELECT processing_token FROM public.material WHERE id = ?", UUID.class, id);
    }

    public boolean fail(long id, UUID token, String message) {
        return jdbc.update("""
                UPDATE public.material SET status = 'FAILED', error_message = ?, processing_started_at = NULL, processing_token = NULL
                WHERE id = ? AND cleanup_pending = false AND deleted_at IS NULL
                  AND (status = 'UPLOADED' OR (status = 'PROCESSING' AND processing_token = ?))
                """, message, id, token) == 1;
    }

    public boolean saveResult(long id, UUID token, MaterialProcessResponse result) {
        // Lock and transition first; rollback restores PROCESSING if any insert fails.
        if (jdbc.update("""
                UPDATE public.material SET status = 'READY', page_count = ?, error_message = NULL,
                    processing_started_at = NULL, processing_token = NULL
                WHERE id = ? AND status = 'PROCESSING' AND processing_token = ? AND cleanup_pending = false AND deleted_at IS NULL
                """, result.pageCount(), id, token) != 1) return false;
        long subjectId = get(id).subjectId();
        for (var page : result.pages())
            jdbc.update("INSERT INTO public.material_page(material_id, page, text) VALUES (?, ?, ?)", id, page.page(), page.text());
        for (var category : result.categories())
            jdbc.update("""
                    INSERT INTO public.category(subject_id, material_id, name, page_start, page_end, summary, "order", question_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PREPARING')
                    """, subjectId, id, category.name(), category.pageStart(), category.pageEnd(), category.summary(), category.order());
        return true;
    }

    public int categoryCount(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM public.category WHERE material_id = ?", Integer.class, id);
    }

    private Material map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Material(rs.getLong("id"), rs.getLong("subject_id"), rs.getString("file_url"),
                rs.getString("file_name"), rs.getString("file_type"), rs.getString("kind"),
                rs.getString("status"), rs.getString("error_message"));
    }

    public record Material(long id, long subjectId, String reference, String name, String type, String kind,
                           String status, String error) {}
    public record MaterialSummary(long id, long subjectId, String fileName, String status, OffsetDateTime createdAt) {}
}

