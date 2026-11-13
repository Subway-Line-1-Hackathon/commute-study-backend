package com.likelion.commute_study_api.material;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.service.GeneratedQuestionWriter;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.question.service.QuestionPregenEventListener;
import com.likelion.commute_study_api.session.repository.SessionRepository;
import com.likelion.commute_study_api.study.StudyReadRepository;
import com.likelion.commute_study_api.wrongnote.WrongNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "SUPABASE_URL=https://auth.example.invalid",
        "material.cleanup-enabled=false", "material.deletion-cleanup-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:material-deletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:db/migration/V4__add_material_file.sql,classpath:db/migration/V5__add_material_lifecycle_tracking.sql,classpath:study-read-test-schema.sql,classpath:wrong-note-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class MaterialDeletionApiTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialDeletionCleanup cleanup;
    @Autowired MaterialDeletionRepository deletionRepository;
    @Autowired GeneratedQuestionWriter questionWriter;
    @Autowired MaterialDeletionTransactions deletion;
    @Autowired MaterialTransactions processing;
    @Autowired MaterialRetentionTransactions retention;
    @Autowired SessionRepository sessions;
    @Autowired StudyReadRepository study;
    @Autowired WrongNoteRepository notes;
    @Autowired QuestionGenerationRepository questions;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean MaterialStorage storage;
    @MockitoBean MaterialProcessor processor;
    @MockitoBean QuestionPregenEventListener listener;
    @MockitoBean JwtDecoder decoder;
    long subject,otherSubject;

    @BeforeEach void prepare() {
        for(String table:List.of("material_delete_object","wrong_note","attempt","session_question","session","question",
                "category","material_page","material_file","material","subject","app_user")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?),(?)", USER,OTHER);
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'내 과목'),(?,'남의 과목')", USER,OTHER);
        subject=jdbc.queryForObject("SELECT id FROM subject WHERE user_id=?",Long.class,USER);
        otherSubject=jdbc.queryForObject("SELECT id FROM subject WHERE user_id=?",Long.class,OTHER);
        doAnswer(inv -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); return null; })
                .when(storage).delete(anyString());
    }
    long material(long subjectId,String state,String type,String ref) {
        jdbc.update("""
                INSERT INTO material(subject_id,file_url,file_name,file_type,kind,status,expires_at)
                VALUES (?,?, '자료', ?, 'LECTURE', ?, ?)
                """, subjectId,ref,type,state,java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        return jdbc.queryForObject("SELECT max(id) FROM material",Long.class);
    }
    void deleteOwn(long id) throws Exception {
        mvc.perform(delete("/api/materials/{id}",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }
    int pending() { return jdbc.queryForObject("SELECT count(*) FROM material_delete_object",Integer.class); }

    @ParameterizedTest @ValueSource(strings={"UPLOADED","PROCESSING"})
    void inProgressReturns409WithoutChangingState(String state) throws Exception {
        long id=material(subject,state,"PDF","private/a.pdf");
        mvc.perform(delete("/api/materials/{id}",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM material WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
        assertThat(pending()).isZero();
    }
    @Test void authAndOwnershipDoNotRevealForeignState() throws Exception {
        long id=material(otherSubject,"PROCESSING","PDF","private/foreign.pdf");
        mvc.perform(delete("/api/materials/{id}",id)).andExpect(status().isUnauthorized());
        for(long target:List.of(id,Long.MAX_VALUE)) mvc.perform(delete("/api/materials/{id}",target)
                .with(jwt().jwt(j->j.subject(USER.toString())))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(pending()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"READY","FAILED","EXPIRED"})
    void allowedStatesAreIdempotentAndRemainDistinctFromExpiry(String state) throws Exception {
        long id=material(subject,state,"PDF",state.equals("EXPIRED")?"":"private/file.pdf");
        deleteOwn(id); deleteOwn(id);
        assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM material WHERE id=?",java.time.OffsetDateTime.class,id)).isNotNull();
        assertThat(pending()).isEqualTo(state.equals("EXPIRED")?0:1);
        mvc.perform(get("/api/materials").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materials.length()").value(0));
        mvc.perform(get("/api/materials/{id}/status",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/materials/{id}/retry",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(storage);
    }
    @ParameterizedTest @ValueSource(strings={"PNG","JPG"})
    void imageCleanupRetriesEachFailedObjectAfterLogicalDelete(String type) throws Exception {
        String a="private/a."+type.toLowerCase(),b="private/b."+type.toLowerCase();
        long id=material(subject,"READY",type,a);
        jdbc.update("INSERT INTO material_file VALUES (?,1,?),(?,2,?)",id,a,id,b);
        deleteOwn(id);
        assertThat(pending()).isEqualTo(2);
        doThrow(new IllegalStateException("offline")).doNothing().when(storage).delete(b);
        cleanup.cleanPending();
        assertThat(pending()).isEqualTo(1);
        // A fresh service instance sees the same durable pending reference after a restart.
        new MaterialDeletionCleanup(deletionRepository,storage).cleanPending();
        assertThat(pending()).isZero();
        verify(storage,times(1)).delete(a);
        verify(storage,times(2)).delete(b);
    }
    @ParameterizedTest @ValueSource(strings={"PDF","PPT"})
    void documentCleanupDeletesOriginal(String type) throws Exception {
        String ref="private/original."+(type.equals("PPT")?"pptx":"pdf");
        long id=material(subject,"READY",type,ref);
        deleteOwn(id);
        assertThat(pending()).isEqualTo(1);
        cleanup.cleanPending();
        assertThat(pending()).isZero();
        verify(storage).delete(ref);
    }
    @Test void naturalRetentionReservationCannotOverwriteUserDeletion() throws Exception {
        long id=material(subject,"READY","PDF","private/race.pdf");
        assertThat(retention.reserve(id,USER)).containsExactly("private/race.pdf");
        deleteOwn(id);
        assertThat(retention.finish(id,USER)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT file_url FROM material WHERE id=?",String.class,id)).isEqualTo("private/race.pdf");
        assertThat(pending()).isEqualTo(1);
        cleanup.cleanPending();
        assertThat(pending()).isZero();
    }
    @Test void concurrentRetryAndDeletionCannotBothWin() throws Exception {
        long id=material(subject,"FAILED","PDF","private/race.pdf");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var delete=pool.submit(() -> { start.await(); try { deletion.delete(id,USER); return "deleted"; }
                catch(ApiException e) { return e.getErrorCode().getCode(); } });
            var retry=pool.submit(() -> { start.await(); try { processing.retry(id,USER); return "processing"; }
                catch(ApiException e) { return e.getErrorCode().getCode(); } });
            start.countDown();
            String d=delete.get(15,TimeUnit.SECONDS),r=retry.get(15,TimeUnit.SECONDS);
            if (d.equals("deleted")) {
                assertThat(r).isIn("NOT_FOUND","STATE_CONFLICT");
                assertThat(jdbc.queryForObject("SELECT deleted_at FROM material WHERE id=?",java.time.OffsetDateTime.class,id)).isNotNull();
            } else {
                assertThat(d).isEqualTo("STATE_CONFLICT");
                assertThat(r).isEqualTo("processing");
                assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id)).isEqualTo("PROCESSING");
            }
        }
    }
    @Test void cleanupQueueSurvivesMaterialRemovalAndRetries() throws Exception {
        long id=material(subject,"READY","PDF","private/file.pdf");
        deleteOwn(id);
        jdbc.update("DELETE FROM material WHERE id=?",id); // Simulates the existing account-deletion cascade.
        assertThat(pending()).isEqualTo(1);
        cleanup.cleanPending();
        assertThat(pending()).isZero();
        verify(storage).delete("private/file.pdf");
    }
    @Test void deletedMaterialIsExcludedFromNewLearningButHistoryRemainsVisible() throws Exception {
        long id=material(subject,"READY","PDF","private/file.pdf");
        jdbc.update("INSERT INTO material_page VALUES (?,1,'본문')",id);
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,summary,\"order\",question_status) VALUES (?,?,'단원',1,1,'요약',1,'AVAILABLE')",subject,id);
        long category=jdbc.queryForObject("SELECT max(id) FROM category",Long.class);
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem,answer,origin,explanation) VALUES (?,'OX',2,'문제','O','PREGEN','해설')",category);
        long question=jdbc.queryForObject("SELECT max(id) FROM question",Long.class);
        jdbc.update("INSERT INTO session(user_id,mode,status) VALUES (?,'REVIEW','ENDED')",USER);
        long session=jdbc.queryForObject("SELECT max(id) FROM session",Long.class);
        jdbc.update("INSERT INTO session_question VALUES (?,?,1)",session,question);
        jdbc.update("INSERT INTO attempt(user_id,question_id,session_id,answer,correct) VALUES (?,?,?,'X',false)",USER,question,session);
        jdbc.update("INSERT INTO wrong_note VALUES (?,?,1,'OPEN',CURRENT_TIMESTAMP)",USER,question);
        deleteOwn(id);
        for(String table:List.of("material_page","category","question","session","session_question","attempt","wrong_note"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).as(table).isEqualTo(1);
        assertThat(study.categories(subject,USER)).isEmpty();
        assertThat(sessions.findOwnedCategories(USER,List.of(category))).isEmpty();
        assertThat(sessions.findCandidates(USER,List.of(),List.of("OX"),2,100)).isEmpty();
        assertThat((Boolean) new TransactionTemplate(txManager).execute(tx -> sessions.lockActiveMaterialsForSelection(USER,List.of(question),List.of(category)))).isFalse();
        assertThat(questions.findCategory(category)).isEmpty();
        assertThat((Boolean) new TransactionTemplate(txManager).execute(tx -> questions.lockActiveMaterialForCategory(category))).isFalse();
        assertThat(study.explanation(question,USER).explanation()).isEqualTo("해설");
        assertThat(notes.list(subject,USER,null,100,50)).extracting(WrongNoteRepository.Item::questionId).containsExactly(question);
    }
    @Test void lateAiResultCannotRecreateQuestionsAfterDeletion() throws Exception {
        long id=material(subject,"READY","PDF","private/late.pdf");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',1,1,1,'PREPARING')",subject,id);
        long category=jdbc.queryForObject("SELECT max(id) FROM category",Long.class);
        var target=questions.findCategory(category).orElseThrow();
        deleteOwn(id);
        var item=new QuestionGenerateResponse.GeneratedQuestion("OX",2,"늦은 문제",List.of(),"O",List.of(),List.of(),"해설",1,"근거",List.of());
        var result=new QuestionGenerateResponse(List.of(item),List.of(),null,null);
        assertThat(questionWriter.save(target,result,"PREGEN")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question",Integer.class)).isZero();
    }
    @Test void deletingRequestedCategoryWithoutSelectedQuestionRejectsStaleSelection() throws Exception {
        long kept=material(subject,"READY","PDF","private/kept.pdf");
        long deleted=material(subject,"READY","PDF","private/deleted.pdf");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'유지',1,1,1,'AVAILABLE'),(?,?,'삭제',1,1,2,'AVAILABLE')",subject,kept,subject,deleted);
        var categories=jdbc.queryForList("SELECT id FROM category ORDER BY id",Long.class);
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem) VALUES (?,'OX',2,'문제')",categories.getFirst());
        long q=jdbc.queryForObject("SELECT max(id) FROM question",Long.class);
        deleteOwn(deleted);
        Boolean valid=new TransactionTemplate(txManager).execute(tx -> sessions.lockActiveMaterialsForSelection(USER,List.of(q),categories));
        assertThat(valid).isFalse();
    }
    @Test void selectionAndDeletionAreSerializedByMaterialLock() throws Exception {
        long id=material(subject,"READY","PDF","private/lock.pdf");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',1,1,1,'AVAILABLE')",subject,id);
        long category=jdbc.queryForObject("SELECT max(id) FROM category",Long.class);
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem) VALUES (?,'OX',2,'문제')",category);
        long q=jdbc.queryForObject("SELECT max(id) FROM question",Long.class);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var deletionStarted=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var selection=pool.submit(() -> new TransactionTemplate(txManager).execute(tx -> {
                boolean active=sessions.lockActiveMaterialsForSelection(USER,List.of(q),List.of(category));
                locked.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("lock timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return active;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var deleted=pool.submit(() -> { deletionStarted.countDown(); deletion.delete(id,USER); return true; });
            assertThat(deletionStarted.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deleted.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(selection.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(deleted.get(10,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }
    @Test void mixedImagesAllHaveIndependentCleanupIntents() throws Exception {
        long id=material(subject,"READY","PNG","private/first.png");
        jdbc.update("INSERT INTO material_file VALUES (?,1,'private/first.png'),(?,2,'private/second.jpg')",id,id);
        deleteOwn(id);
        assertThat(pending()).isEqualTo(2);
        cleanup.cleanPending();
        verify(storage).delete("private/first.png");
        verify(storage).delete("private/second.jpg");
        assertThat(pending()).isZero();
    }
    @Test void swaggerDocumentsDeletionAndBearerAuth() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/materials/{id}'].delete.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/materials/{id}'].delete.responses['204']").exists());
    }
    @Test void queueInsertFailureRollsBackLogicalDeletion() throws Exception {
        long id=material(subject,"READY","PDF","private/file.pdf");
        jdbc.execute("ALTER TABLE material_delete_object ADD CONSTRAINT fail_enqueue CHECK (object_ref <> 'private/file.pdf')");
        try {
            mvc.perform(delete("/api/materials/{id}",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                    .andExpect(status().isInternalServerError());
            assertThat(jdbc.queryForObject("SELECT deleted_at FROM material WHERE id=?",java.time.OffsetDateTime.class,id)).isNull();
            assertThat(pending()).isZero();
        } finally { jdbc.execute("ALTER TABLE material_delete_object DROP CONSTRAINT fail_enqueue"); }
    }
}
