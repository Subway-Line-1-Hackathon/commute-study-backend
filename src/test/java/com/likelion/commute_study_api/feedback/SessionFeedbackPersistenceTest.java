package com.likelion.commute_study_api.feedback;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.likelion.commute_study_api.ai.feedback.FeedbackResponse;
import com.likelion.commute_study_api.feedback.repository.SessionFeedbackRepository;
import com.likelion.commute_study_api.feedback.service.SessionFeedbackService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual migration/JDBC/transaction tests against fresh local H2 databases only. */
class SessionFeedbackPersistenceTest {

	@Configuration
	@EnableTransactionManagement
	static class TestConfig {
		@Bean DriverManagerDataSource dataSource() {
			return new DriverManagerDataSource("jdbc:h2:mem:feedback-" + UUID.randomUUID()
					+ ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
		}
		@Bean JdbcTemplate jdbcTemplate(DriverManagerDataSource dataSource) { return new JdbcTemplate(dataSource); }
		@Bean PlatformTransactionManager transactionManager(DriverManagerDataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}
		@Bean SessionFeedbackRepository repository(JdbcTemplate jdbc) { return new SessionFeedbackRepository(jdbc); }
	}

	static class Database implements AutoCloseable {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class);
		final JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
		final SessionFeedbackRepository repository = context.getBean(SessionFeedbackRepository.class);
		final TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
		final Connection keeper;

		Database() throws Exception {
			// Keep the migration connection alive: H2 CHECK IN evaluation may retain its creating session.
			keeper = context.getBean(DriverManagerDataSource.class).getConnection();
			jdbc.execute("CREATE TABLE session(id BIGINT PRIMARY KEY, mode VARCHAR(20) DEFAULT 'REVIEW', status VARCHAR(20), feedback TEXT)");
			jdbc.execute("CREATE TABLE attempt(id BIGINT PRIMARY KEY, session_id BIGINT, question_id BIGINT, correct BOOLEAN)");
			jdbc.execute("CREATE TABLE session_question(session_id BIGINT, question_id BIGINT, \"order\" INT)");
			jdbc.execute("CREATE TABLE question(id BIGINT PRIMARY KEY, stem TEXT, concept_tags JSONB)");
		}

		void migrate() {
			new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__add_session_feedback_status.sql"))
					.execute(new SingleConnectionDataSource(keeper, true));
		}
		void session(long id, String status) { jdbc.update("INSERT INTO session(id,status) VALUES (?,?)", id, status); }
		void gradedAnswer(long session) {
			jdbc.update("INSERT INTO attempt(id,session_id,question_id,correct) VALUES (?,?,?,true)", session, session, session);
		}
		String status(long id) { return jdbc.queryForObject("SELECT feedback_status FROM session WHERE id=?", String.class, id); }
		String reason(long id) { return jdbc.queryForObject("SELECT feedback_status_reason FROM session WHERE id=?", String.class, id); }
		@Override public void close() throws Exception { keeper.close(); context.close(); }
	}

	@Test
	void migrationPreservesResultsAndExplicitlySkipsHistoricalGenerationWithoutGuessingFailure() throws Exception {
		try (var db = new Database()) {
			db.jdbc.update("INSERT INTO session(id,status,feedback) VALUES (1,'COMPLETED','기존 결과'),(2,'ENDED',null),(3,'COMPLETED','   '),(4,'READY',null),(5,'IN_PROGRESS',null),(7,'ENDED',?)", "\t\n");
			db.jdbc.update("INSERT INTO attempt VALUES (1,1,7,true)");
			db.jdbc.update("INSERT INTO session_question VALUES (1,7,1)");
			db.migrate();

			assertThat(db.status(1)).isEqualTo("READY");
			assertThat(db.jdbc.queryForObject("SELECT feedback FROM session WHERE id=1", String.class)).isEqualTo("기존 결과");
			for (long id : List.of(2L, 3L, 7L)) {
				assertThat(db.status(id)).isEqualTo("SKIPPED");
				assertThat(db.reason(id)).isEqualTo("LEGACY_NOT_REGENERATED");
			}
			assertThat(db.jdbc.queryForObject("SELECT feedback FROM session WHERE id=3", String.class)).isNull();
			assertThat(db.status(4)).isEqualTo("PENDING");
			assertThat(db.status(5)).isEqualTo("PENDING");
			assertThat(db.jdbc.queryForObject("SELECT count(*) FROM attempt", Integer.class)).isEqualTo(1);
			assertThat(db.jdbc.queryForObject("SELECT count(*) FROM session_question", Integer.class)).isEqualTo(1);
			db.session(6, "READY");
			assertThat(db.status(6)).isEqualTo("PENDING");
			assertThatThrownBy(() -> db.jdbc.update("UPDATE session SET feedback_status='UNKNOWN' WHERE id=6"))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> db.jdbc.update("UPDATE session SET feedback_status=null WHERE id=6"))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void preparationCommitsWithEndAndRollsBackWithEnd() throws Exception {
		try (var db = new Database()) {
			db.migrate();
			for (long id = 1; id <= 6; id++) db.session(id, "IN_PROGRESS");
			for (long id : List.of(1L, 2L, 4L)) db.gradedAnswer(id);
			db.tx.executeWithoutResult(tx -> {
				db.jdbc.update("UPDATE session SET status='ENDED' WHERE id<=5");
				db.repository.prepare(1, "NEW_START");
				db.repository.prepare(2, "NEXT_DAY");
				db.repository.prepare(3, "ARRIVED");
				db.repository.prepare(4, "QUIT");
				db.repository.prepare(5, null);
			});
			assertThat(db.reason(1)).isEqualTo("NEW_START");
			assertThat(db.reason(2)).isEqualTo("NEXT_DAY");
			assertThat(db.reason(3)).isEqualTo("NO_GRADED_ANSWERS");
			assertThat(db.status(4)).isEqualTo("PENDING");
			assertThat(db.reason(5)).isEqualTo("NO_GRADED_ANSWERS");

			db.tx.executeWithoutResult(tx -> {
				db.jdbc.update("UPDATE session SET status='ENDED' WHERE id=6");
				db.repository.prepare(6, "NEW_START");
				tx.setRollbackOnly();
			});
			assertThat(db.status(6)).isEqualTo("PENDING");
			assertThat(db.reason(6)).isNull();
		}
	}

	@Test
	void claimedGenerationAndTerminalUpdatesDoNotOverwriteEachOther() throws Exception {
		try (var db = new Database()) {
			db.migrate(); db.session(1, "COMPLETED"); db.session(2, "ENDED"); db.session(3, "IN_PROGRESS");
			assertThat(db.repository.claimPending(1)).isTrue();
			assertThat(db.repository.claimPending(1)).isFalse();
			assertThat(db.repository.failDispatch(1)).isFalse();
			assertThat(db.repository.saveIfAbsent(1, "새 피드백")).isTrue();
			assertThat(db.repository.markTerminal(1, FeedbackStatus.FAILED, "AI_ERROR")).isFalse();
			assertThat(db.repository.saveIfAbsent(1, "늦은 결과")).isFalse();
			assertThat(db.status(1)).isEqualTo("READY");
			assertThat(db.jdbc.queryForObject("SELECT feedback FROM session WHERE id=1", String.class)).isEqualTo("새 피드백");
			assertThat(db.reason(1)).isNull();
			assertThat(db.repository.markTerminal(2, FeedbackStatus.SKIPPED, "NO_GRADED_ANSWERS")).isTrue();
			assertThat(db.repository.claimPending(2)).isFalse();
			assertThat(db.repository.saveIfAbsent(2, "금지된 결과")).isFalse();
			assertThat(db.repository.claimPending(3)).isFalse();
			assertThat(db.repository.markTerminal(3, FeedbackStatus.FAILED, "AI_ERROR")).isFalse();
			assertThat(db.repository.saveIfAbsent(99, "없는 세션")).isFalse();
		}
	}

	@Test
	void dispatchFailurePersistsFromAfterCommitDespiteBoundOriginalConnection() throws Exception {
		try (var db = new Database()) {
			db.migrate(); db.session(1, "IN_PROGRESS"); db.gradedAnswer(1);
			db.tx.executeWithoutResult(tx -> {
				db.jdbc.update("UPDATE session SET status='ENDED' WHERE id=1");
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override public void afterCommit() { db.repository.failDispatch(1); }
				});
			});
			assertThat(db.status(1)).isEqualTo("FAILED");
			assertThat(db.reason(1)).isEqualTo("DISPATCH_REJECTED");
		}
	}

	@Test
	void generationPersistsReadyFailedAndSkippedRatherThanInferringThemOnRead() throws Exception {
		try (var db = new Database()) {
			db.migrate();
			for (long id = 1; id <= 4; id++) { db.session(id, "COMPLETED"); if (id < 4) db.gradedAnswer(id); }
			var service = new SessionFeedbackService(db.repository, request -> {
				if (request.sessionId() == 2) throw new IllegalStateException("AI unavailable");
				return new FeedbackResponse(request.sessionId() == 3 ? " " : "성공");
			});
			for (long id = 1; id <= 4; id++) service.generate(id);
			assertThat(db.status(1)).isEqualTo("READY");
			assertThat(db.reason(2)).isEqualTo("AI_ERROR");
			assertThat(db.status(2)).isEqualTo("FAILED");
			assertThat(db.reason(3)).isEqualTo("EMPTY_RESPONSE");
			assertThat(db.status(3)).isEqualTo("FAILED");
			assertThat(db.reason(4)).isEqualTo("NO_GRADED_ANSWERS");
			assertThat(db.status(4)).isEqualTo("SKIPPED");
		}
	}
}
