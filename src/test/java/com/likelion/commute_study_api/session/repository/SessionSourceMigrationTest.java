package com.likelion.commute_study_api.session.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
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
            jdbc.update("INSERT INTO session(id,category_ids) VALUES (4,ARRAY[11]),(7,NULL)");
            assertThat(jdbc.queryForObject("SELECT source FROM session WHERE id=4",String.class)).isEqualTo("categories");
            // A legacy caller that omits source is categorized even when it omitted categories too.
            assertThat(jdbc.queryForObject("SELECT source FROM session WHERE id=7",String.class)).isEqualTo("categories");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO session(id,source) VALUES (6,NULL)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO session(id,source) VALUES (5,'invalid')"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            migrationDataSource.destroy();
        }
    }

    @Test
    void developmentSeedSessionInsertWorksWithMigratedSchema() throws Exception {
        String script = Files.readString(Path.of("scripts", "dev_seed.sql"));
        var statement = Pattern.compile("(?is)insert\\s+into\\s+public\\.session\\s*\\([^;]+?returning\\s+id\\s+into\\s+v_session_id\\s*;")
                .matcher(script);
        assertThat(statement.find()).as("시드의 세션 INSERT").isTrue();
        String seedInsert = statement.group()
                .replaceAll("(?is)\\s+returning\\s+id\\s+into\\s+v_session_id\\s*;", "")
                .replace("v_user_id", "'11111111-1111-1111-1111-111111111111'")
                .replace("v_category_id1", "11");
        assertThat(statement.find()).as("세션 INSERT는 한 건").isFalse();
        assertThat(seedInsert.substring(0, seedInsert.toLowerCase(java.util.Locale.ROOT).indexOf("values")))
                .as("시드는 기본값에 의존하지 않고 source를 명시한다").containsPattern("(?i)\\bsource\\b");
        assertThat(seedInsert).contains("'categories'");

        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL";
        var migrationDataSource = new SingleConnectionDataSource(url, "sa", "", true);
        try {
            var jdbc = new JdbcTemplate(migrationDataSource);
            jdbc.execute("""
                    CREATE TABLE session (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id UUID,
                        category_ids BIGINT ARRAY, question_types JSONB, difficulty INT, study_min INT,
                        mode VARCHAR(20), status VARCHAR(20), started_at TIMESTAMP WITH TIME ZONE,
                        ended_at TIMESTAMP WITH TIME ZONE)
                    """);
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V6__add_session_source.sql")).execute(migrationDataSource);
            jdbc.update(seedInsert);
            assertThat(jdbc.queryForObject("SELECT source FROM session",String.class)).isEqualTo("categories");
            assertThat(jdbc.queryForObject("SELECT category_ids[1] FROM session",Long.class)).isEqualTo(11L);
            assertThat(jdbc.queryForObject("SELECT mode FROM session",String.class)).isEqualTo("CURRICULUM");
            assertThat(jdbc.queryForObject("SELECT status FROM session",String.class)).isEqualTo("COMPLETED");
        } finally {
            migrationDataSource.destroy();
        }
    }
}
