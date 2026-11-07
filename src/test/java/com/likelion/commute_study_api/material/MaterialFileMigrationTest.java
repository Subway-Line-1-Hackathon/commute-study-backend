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
