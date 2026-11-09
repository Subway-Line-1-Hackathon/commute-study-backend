package com.likelion.commute_study_api.session.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Runs the actual V6 SQL only against an isolated in-memory H2 database. */
class SessionSourceMigrationTest {
    @Test
    void backfillsLegacySessionsAndRequiresValidSourceOnNewSessions() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        // H2 2.4.240 can retain the CHECK IN-list evaluator's creating session.
        // Keep that connection open; verify the results through separate connections.
        var migrationDataSource = new SingleConnectionDataSource(url, "sa", "", true);
        try {
            // Open the keeper before creating the schema on independently opened connections.
            new JdbcTemplate(migrationDataSource).execute("SELECT 1");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
            jdbc.execute("CREATE TABLE session (id BIGINT PRIMARY KEY, category_ids BIGINT ARRAY)");
            jdbc.execute("CREATE TABLE session_question (session_id BIGINT, question_id BIGINT)");
            jdbc.execute("CREATE TABLE attempt (session_id BIGINT, question_id BIGINT)");
            jdbc.execute("CREATE TABLE wrong_note (question_id BIGINT, status VARCHAR(20))");
            jdbc.update("INSERT INTO session VALUES (1,NULL),(2,ARRAY[11])");
            jdbc.update("INSERT INTO session_question VALUES (2,101)");
            jdbc.update("INSERT INTO attempt VALUES (2,101)");
            jdbc.update("INSERT INTO wrong_note VALUES (101,'OPEN')");

            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__add_session_source.sql")).execute(migrationDataSource);

            assertThat(jdbc.queryForObject("SELECT source FROM session WHERE id=1",String.class)).isEqualTo("wrongNotes");
            assertThat(jdbc.queryForObject("SELECT source FROM session WHERE id=2",String.class)).isEqualTo("categories");
            assertThat(jdbc.queryForObject("SELECT category_ids[1] FROM session WHERE id=2",Long.class)).isEqualTo(11L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM session_question",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attempt",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM wrong_note",String.class)).isEqualTo("OPEN");
            jdbc.update("INSERT INTO session(id,category_ids,source) VALUES (3,ARRAY[11],'wrongNotes')");
            assertThat(jdbc.queryForObject("SELECT source FROM session WHERE id=3",String.class)).isEqualTo("wrongNotes");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO session(id) VALUES (4)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO session(id,source) VALUES (5,'invalid')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            migrationDataSource.destroy();
        }
    }
}
