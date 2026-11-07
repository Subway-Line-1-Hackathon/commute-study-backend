package com.likelion.commute_study_api.material;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class MaterialFileMigrationTest {
    @Test
    void v5GivesLegacyProcessingFreshGraceWithoutChangingUploadTime() {
        var ds=new SingleConnectionDataSource("jdbc:h2:mem:lifecycle-"+UUID.randomUUID()+";MODE=PostgreSQL","sa","",true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("material-test-schema.sql"),
                    new ClassPathResource("db/migration/V4__add_material_file.sql")).execute(ds);
            var jdbc=new JdbcTemplate(ds);
            jdbc.update("INSERT INTO app_user(id) VALUES (?)",UUID.randomUUID());
            jdbc.update("INSERT INTO subject(user_id,name) SELECT id,'과목' FROM app_user");
            jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status,created_at) SELECT id,'private/a.pdf','PDF','PROCESSING',TIMESTAMP '2020-01-01 00:00:00' FROM subject");
            jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status) SELECT id,'private/b.pdf','PDF','READY' FROM subject");
            var before=java.time.Instant.now().minusSeconds(1);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V5__add_material_lifecycle_tracking.sql")).execute(ds);
            var started=jdbc.queryForObject("SELECT processing_started_at FROM material WHERE status='PROCESSING'",java.sql.Timestamp.class).toInstant();
            assertThat(started).isAfter(before).isBefore(java.time.Instant.now().plusSeconds(1));
            assertThat(jdbc.queryForObject("SELECT EXTRACT(YEAR FROM created_at) FROM material WHERE status='PROCESSING'",Integer.class)).isEqualTo(2020);
            assertThat(jdbc.queryForObject("SELECT processing_started_at FROM material WHERE status='READY'",java.sql.Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material WHERE cleanup_pending=false AND processing_token IS NULL",Integer.class)).isEqualTo(2);
            assertThat(new MaterialRetentionRepository(jdbc).staleProcessing(java.time.Instant.now().minusSeconds(600))).isEmpty();
        } finally { ds.destroy(); }
    }

    @Test
    void v4BackfillsOnlyPngAndEnforcesOrderAndCascade() {
        var dataSource=new SingleConnectionDataSource("jdbc:h2:mem:migration-"+UUID.randomUUID()+";MODE=PostgreSQL", "sa", "", true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("material-test-schema.sql")).execute(dataSource);
            var jdbc=new JdbcTemplate(dataSource);
            var user=UUID.randomUUID();
            jdbc.update("INSERT INTO app_user(id) VALUES (?)",user);
            jdbc.update("INSERT INTO subject(user_id,name) VALUES (?, '수학')",user);
            long subject=jdbc.queryForObject("SELECT id FROM subject",Long.class);
            jdbc.update("INSERT INTO material(subject_id,file_url,file_type) VALUES (?, 'private/old.png', 'PNG')",subject);
            jdbc.update("INSERT INTO material(subject_id,file_url,file_type) VALUES (?, 'private/old.pdf', 'PDF')",subject);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V4__add_material_file.sql")).execute(dataSource);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_file",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT file_url FROM material_file",String.class)).isEqualTo("private/old.png");
            long material=jdbc.queryForObject("SELECT material_id FROM material_file",Long.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO material_file VALUES (?,21,'out-of-range')",material))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO material_file VALUES (?,1,'duplicate')",material))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbc.update("DELETE FROM material WHERE id=?",material);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_file",Integer.class)).isZero();
        } finally { dataSource.destroy(); }
    }
}
