package com.likelion.commute_study_api.subject;

import java.util.List;
import java.util.concurrent.*;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.dto.SessionSource;
import com.likelion.commute_study_api.session.service.SessionWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class SubjectDeletionPostgresTest extends SubjectDeletionApiTest {
    @Autowired SessionWriter writer;
    @Override boolean postgres() { return true; }

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String port=System.getenv("R9_TEST_PG_PORT");
        if(port==null || !port.matches("[0-9]{1,5}"))
            throw new IllegalStateException("Run scripts/test-r9-postgres.ps1 against its disposable container");
        registry.add("spring.datasource.url",()->"jdbc:postgresql://127.0.0.1:"+port+"/r9validation?stringtype=unspecified");
        registry.add("spring.datasource.username",()->"postgres");
        registry.add("spring.datasource.password",()->"r9-isolated-test");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.sql.init.mode",()->"never");
    }

    @Test void allTenMigrationsAreAppliedExactlyOnce() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",String.class))
                .containsExactly("1","2","3","4","5","6","7","8","9","10");
    }
    @Test void actualSessionInsertLosingToDeletionIsRejected() throws Exception {
        long c=category(material("READY","PDF","new-session.pdf")), q=question(c);
        deleteOwn();
        assertThat(outcome(()->writer.save(USER,List.of(c),List.of("OX"),2,10,StudyMode.REVIEW,SessionSource.CATEGORIES,List.of(q))))
                .isEqualTo("STATE_CONFLICT");
        assertThat(count("session")).isZero();
    }
    @Test void committedSessionSurvivesConcurrentSubjectDeletion() throws Exception {
        long c=category(material("READY","PDF","existing-session.pdf")),q=question(c);
        var saved=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var session=pool.submit(()->new TransactionTemplate(txManager).execute(tx->{
                long id=writer.save(USER,List.of(c),List.of("OX"),2,10,StudyMode.REVIEW,SessionSource.CATEGORIES,List.of(q));
                saved.countDown(); await(release); return id;
            }));
            assertThat(saved.await(10,TimeUnit.SECONDS)).isTrue();
            var d=pool.submit(()->{ deletion.delete(subject,USER); return true; });
            try { assertThatThrownBy(()->d.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(session.get(10,TimeUnit.SECONDS)).isPositive(); assertThat(d.get(10,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(count("session")).isEqualTo(1); assertThat(count("session_question")).isEqualTo(1);
    }
}
