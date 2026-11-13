package com.likelion.commute_study_api.material;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

class MaterialDeletionMigrationTest {
    @Test void migrationPreservesExistingMaterialAndQueueOutlivesMaterial() {
        String url="jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL";
        var ds=new SingleConnectionDataSource(url,"sa","",true);
        try {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE material(id BIGINT PRIMARY KEY, status VARCHAR(20), file_url TEXT)");
            jdbc.update("INSERT INTO material VALUES (1,'READY','private/file.pdf')");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V9__add_material_soft_deletion.sql")).execute(ds);
            assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id=1",String.class)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM material WHERE id=1",java.time.OffsetDateTime.class)).isNull();
            jdbc.update("INSERT INTO material_delete_object(material_id,object_ref) VALUES (1,'private/file.pdf')");
            jdbc.update("DELETE FROM material WHERE id=1");
            assertThat(jdbc.queryForObject("SELECT object_ref FROM material_delete_object",String.class)).isEqualTo("private/file.pdf");
            assertThat(jdbc.queryForObject("SELECT retry_order_at FROM material_delete_object",java.time.OffsetDateTime.class)).isNotNull();
        } finally { ds.destroy(); }
    }
}
