package com.likelion.commute_study_api.subject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import com.likelion.commute_study_api.ai.question.QuestionAiClient;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.*;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.service.QuestionPregenEventListener;
import com.likelion.commute_study_api.question.service.QuestionPregenService;
import com.likelion.commute_study_api.session.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "SUPABASE_URL=https://auth.example.invalid",
        "material.cleanup-enabled=false", "material.deletion-cleanup-enabled=false", "question.pregen-retry-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:subject-deletion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:db/migration/V4__add_material_file.sql,classpath:db/migration/V5__add_material_lifecycle_tracking.sql,classpath:study-read-test-schema.sql,classpath:wrong-note-test-schema.sql,classpath:report-test-schema.sql,classpath:subject-deletion-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class SubjectDeletionApiTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubjectDeletionService deletion;
    @Autowired SubjectRepository subjects;
    @Autowired SubjectService subjectService;
    @Autowired MaterialService materialService;
    @Autowired MaterialTransactions processing;
    @Autowired MaterialDeletionTransactions materialDeletion;
    @Autowired MaterialDeletionRepository queue;
    @Autowired MaterialDeletionCleanup cleanup;
    @Autowired QuestionGenerationRepository questions;
    @Autowired QuestionPregenService pregen;
    @Autowired SessionRepository sessions;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean MaterialStorage storage;
    @MockitoBean MaterialProcessor processor;
    @MockitoBean QuestionPregenEventListener listener;
    @MockitoBean QuestionAiClient questionAi;
    @MockitoBean JwtDecoder decoder;
    long subject, otherSubject;

    boolean postgres() { return false; }

    @BeforeEach void prepare() {
        for (String table:List.of("material_delete_object","wrong_note","attempt","session_question","session","question",
                "category","material_page","material_file","material","subject","commute_profile","app_user"))
            jdbc.update("DELETE FROM "+table);
        if (postgres()) jdbc.update("INSERT INTO auth.users(id) VALUES (?),(?) ON CONFLICT DO NOTHING",USER,OTHER);
        jdbc.update("INSERT INTO app_user(id,nickname) VALUES (?,'tester'),(?,'other')",USER,OTHER);
        jdbc.update("INSERT INTO commute_profile(user_id,one_way_min) VALUES (?,20)",USER);
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'subject'),(?,'other')",USER,OTHER);
        subject=jdbc.queryForObject("SELECT id FROM subject WHERE user_id=?",Long.class,USER);
        otherSubject=jdbc.queryForObject("SELECT id FROM subject WHERE user_id=?",Long.class,OTHER);
    }
    Jwt token() { return Jwt.withTokenValue("test").header("alg","none").subject(USER.toString()).build(); }
    long material(String state,String type,String ref) {
        jdbc.update("""
                INSERT INTO material(subject_id,file_url,file_name,file_type,kind,status,expires_at,size)
                VALUES (?,?,'file',CAST(? AS material_file_type),'LECTURE',CAST(? AS material_status),?,100)
                """,subject,ref,type,state,java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        return jdbc.queryForObject("SELECT max(id) FROM material",Long.class);
    }
    long category(long material) {
        jdbc.update("INSERT INTO material_page VALUES (?,1,'page')",material);
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'category',1,1,1,'PREPARING')",subject,material);
        return jdbc.queryForObject("SELECT max(id) FROM category",Long.class);
    }
    long question(long category) {
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem,answer,origin,explanation) VALUES (?,'OX',2,'question','O','PREGEN','explanation')",category);
        return jdbc.queryForObject("SELECT max(id) FROM question",Long.class);
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    boolean deleted() { return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM subject WHERE id=?",Boolean.class,subject); }
    void deleteOwn() throws Exception {
        mvc.perform(delete("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }
    @Test void authenticationAndOwnership() throws Exception {
        mvc.perform(delete("/api/subjects/{id}",subject)).andExpect(status().isUnauthorized());
        for(long id:List.of(otherSubject,Long.MAX_VALUE)) mvc.perform(delete("/api/subjects/{id}",id)
                .with(jwt().jwt(j->j.subject(USER.toString())))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(deleted()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"UPLOADED","PROCESSING"})
    void busyMaterialRollsBackEveryTombstoneAndQueueEntry(String state) throws Exception {
        material("READY","PDF","ready.pdf"); material(state,"PDF","busy.pdf");
        mvc.perform(delete("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(deleted()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material WHERE deleted_at IS NOT NULL",Integer.class)).isZero();
        assertThat(count("material_delete_object")).isZero();
        verifyNoInteractions(storage);
    }
    @Test void allSupportedFilesAreQueuedOnceAndFailuresSurviveServiceRestart() throws Exception {
        material("READY","PDF","original.pdf"); material("FAILED","PPT","original.pptx");
        long images=material("READY","PNG","first.png");
        jdbc.update("INSERT INTO material_file VALUES (?,1,'first.png'),(?,2,'second.jpg')",images,images);
        material("EXPIRED","PDF","");
        deleteOwn(); deleteOwn();
        assertThat(count("material")).isEqualTo(4);
        assertThat(count("material_delete_object")).isEqualTo(4);
        verifyNoInteractions(storage);
        doThrow(new IllegalStateException("offline")).doNothing().when(storage).delete("second.jpg");
        cleanup.cleanPending();
        assertThat(count("material_delete_object")).isEqualTo(1);
        new MaterialDeletionCleanup(queue,storage).cleanPending();
        assertThat(count("material_delete_object")).isZero();
        verify(storage,times(2)).delete("second.jpg");
        verify(storage).delete("original.pdf"); verify(storage).delete("original.pptx"); verify(storage).delete("first.png");
    }
    @Test void lastSubjectDeletionKeepsActiveAndPatchCannotResurrectIt() throws Exception {
        mvc.perform(patch("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString())))
                .contentType("application/json").content("{\"name\":\"renamed\",\"examDate\":\"2099-12-15\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("renamed"));
        deleteOwn(); deleteOwn();
        mvc.perform(get("/api/subjects").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjects.length()").value(0));
        mvc.perform(get("/api/users/me").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hasSubject").value(false));
        mvc.perform(patch("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString())))
                .contentType("application/json").content("{\"name\":\"resurrected\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT name FROM subject WHERE id=?",String.class,subject)).isEqualTo("renamed");
    }
    @Test void historyAndReportsRemainReadableButNewSelectionIsEmpty() throws Exception {
        long c=category(material("READY","PDF","history.pdf")),q=question(c);
        jdbc.update("INSERT INTO session(user_id,mode,status,source,feedback,feedback_status) VALUES (?,'REVIEW','ENDED','categories','feedback','READY')",USER);
        long s=jdbc.queryForObject("SELECT max(id) FROM session",Long.class);
        jdbc.update("INSERT INTO session_question VALUES (?,?,1)",s,q);
        jdbc.update("INSERT INTO attempt(user_id,question_id,session_id,answer,correct) VALUES (?,?,?,'X',false)",USER,q,s);
        jdbc.update("INSERT INTO wrong_note(user_id,question_id) VALUES (?,?)",USER,q);
        deleteOwn();
        for(String table:List.of("material_page","category","question","session","session_question","attempt","wrong_note"))
            assertThat(count(table)).as(table).isEqualTo(1);
        mvc.perform(get("/api/materials").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materials.length()").value(0));
        mvc.perform(get("/api/subjects/{id}/categories",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories.length()").value(0));
        mvc.perform(get("/api/questions/{id}/explanation",q).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.explanation").value("explanation"));
        mvc.perform(get("/api/subjects/{id}/wrong-notes",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sessions/{id}/report",s).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.today.answered").value(1))
                .andExpect(jsonPath("$.feedback").value("feedback"));
        mvc.perform(get("/api/subjects/{id}/report",subject).param("scope","all").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.average").value(0.0));
        assertThat(sessions.findOwnedCategories(USER,List.of(c))).isEmpty();
        assertThat(sessions.findCandidates(USER,List.of(),List.of("OX"),2,100)).isEmpty();
        assertThat(new TransactionTemplate(txManager).<Boolean>execute(tx->sessions.lockActiveMaterialsForSelection(USER,List.of(q),List.of(c)))).isFalse();
    }
    @Test void inFlightR7GenerationCannotSaveOrRefillAfterDeletion() throws Exception {
        long c=category(material("READY","PDF","generation.pdf"));
        var response=new CompletableFuture<QuestionGenerateResponse>(); var entered=new CountDownLatch(1);
        when(questionAi.generate(any())).thenAnswer(inv->{ entered.countDown(); return response; });
        var result=pregen.pregenerate(questions.findCategory(c).orElseThrow());
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        deleteOwn();
        var item=new QuestionGenerateResponse.GeneratedQuestion("OX",2,"late",List.of(),"O",List.of(),List.of(),"explanation",1,"evidence",List.of());
        response.complete(new QuestionGenerateResponse(List.of(item),List.of(),null,null));
        assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
        assertThat(count("question")).isZero(); verify(questionAi,times(1)).generate(any());
    }
    @Test void uploadLosingToDeletionCompensatesAllObjectsAndRetriesFailedCleanup() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(storage.reference(eq(USER),anyString())).thenReturn("race.png","race.jpg");
        doAnswer(inv->{ entered.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); return null; })
                .when(storage).upload(eq("race.png"),any());
        doThrow(new IllegalStateException("offline")).doNothing().when(storage).delete("race.jpg");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var upload=pool.submit(()->{
                try { materialService.upload(token(),subject,List.of(
                    new MockMultipartFile("file","a.png","image/png",new byte[]{1}),
                    new MockMultipartFile("file","b.jpg","image/jpeg",new byte[]{2})),"LECTURE"); return "uploaded"; }
                catch(ApiException e) { return e.getErrorCode().getCode(); }
            });
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try { deleteOwn(); } finally { release.countDown(); }
            assertThat(upload.get(10,TimeUnit.SECONDS)).isEqualTo("NOT_FOUND");
        } finally { release.countDown(); }
        assertThat(count("material")).isZero(); assertThat(count("material_delete_object")).isEqualTo(1);
        new MaterialDeletionCleanup(queue,storage).cleanPending();
        assertThat(count("material_delete_object")).isZero();
        verify(storage).delete("race.png"); verify(storage,times(2)).delete("race.jpg");
    }
    @Test void committedUploadMakesSubjectDeletionConflict() throws Exception {
        when(storage.reference(eq(USER),anyString())).thenReturn("uploaded.pdf");
        var uploaded=materialService.upload(token(),subject,List.of(new MockMultipartFile("file","a.pdf","application/pdf",new byte[]{1})),"LECTURE");
        assertThat(uploaded.materialId()).isPositive();
        mvc.perform(delete("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isConflict());
        assertThat(deleted()).isFalse(); assertThat(count("material_delete_object")).isZero();
    }
    @Test void deletedSubjectRejectsUploadBeforeTouchingStorage() throws Exception {
        deleteOwn();
        assertThatThrownBy(()->materialService.upload(token(),subject,List.of(new MockMultipartFile("file","a.pdf","application/pdf",new byte[]{1})),"LECTURE"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(storage);
    }
    @Test void retryAndDeletionSerializeOnSubject() throws Exception {
        long id=material("FAILED","PDF","retry.pdf"); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var d=pool.submit(()->{ start.await(); return outcome(()->deletion.delete(subject,USER)); });
            var r=pool.submit(()->{ start.await(); return outcome(()->processing.retry(id,USER)); });
            start.countDown();
            String delete=d.get(15,TimeUnit.SECONDS), retry=r.get(15,TimeUnit.SECONDS);
            if(delete.equals("OK")) { assertThat(retry).isEqualTo("NOT_FOUND"); assertThat(deleted()).isTrue(); }
            else { assertThat(delete).isEqualTo("STATE_CONFLICT"); assertThat(retry).isEqualTo("OK"); assertThat(deleted()).isFalse(); }
        }
    }
    String outcome(Runnable action) {
        try { action.run(); return "OK"; } catch(ApiException e) { return e.getErrorCode().getCode(); }
    }
    @Test void concurrentDuplicateDeleteCreatesOneCleanupIntent() throws Exception {
        material("READY","PDF","once.pdf"); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{ start.await(); deletion.delete(subject,USER); return true; });
            var b=pool.submit(()->{ start.await(); deletion.delete(subject,USER); return true; });
            start.countDown(); assertThat(a.get(10,TimeUnit.SECONDS)).isTrue(); assertThat(b.get(10,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("material_delete_object")).isEqualTo(1);
    }
    @Test void queueFailureRollsBackSubjectAndEveryMaterial() throws Exception {
        material("READY","PDF","first.pdf"); material("READY","PDF","reject.pdf");
        jdbc.execute("ALTER TABLE material_delete_object ADD CONSTRAINT reject_test_object CHECK (object_ref <> 'reject.pdf')");
        try {
            mvc.perform(delete("/api/subjects/{id}",subject).with(jwt().jwt(j->j.subject(USER.toString()))))
                    .andExpect(status().isInternalServerError());
            assertThat(deleted()).isFalse(); assertThat(count("material_delete_object")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material WHERE deleted_at IS NOT NULL",Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE material_delete_object DROP CONSTRAINT reject_test_object"); }
    }
    @Test void priorR8DeletionDoesNotDuplicateCleanupAndStaleWorkerCannotReviveIt() throws Exception {
        long id=material("FAILED","PDF","already.pdf");
        materialDeletion.delete(id,USER);
        deleteOwn();
        assertThat(count("material_delete_object")).isEqualTo(1);
        assertThat(processing.claimUpload(id,UUID.randomUUID())).isFalse();
        processing.failed(id,UUID.randomUUID(),"late failure");
        assertThat(jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM material WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void patchWaitsForDeletionAndCannotBringBackSubject() throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var d=pool.submit(()->new TransactionTemplate(txManager).execute(tx->{
                deletion.delete(subject,USER); locked.countDown(); await(release); return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var patch=pool.submit(()->outcome(()->subjectService.update(token(),subject,new SubjectPatchRequest(true,"late",false,null))));
            try { assertThatThrownBy(()->patch.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(d.get(10,TimeUnit.SECONDS)).isTrue(); assertThat(patch.get(10,TimeUnit.SECONDS)).isEqualTo("NOT_FOUND");
        } finally { release.countDown(); }
    }
    @Test void selectionLockSerializesWithSubjectDeletion() throws Exception {
        long c=category(material("READY","PDF","select.pdf")),q=question(c);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var selection=pool.submit(()->new TransactionTemplate(txManager).execute(tx->{
                boolean active=sessions.lockActiveMaterialsForSelection(USER,List.of(q),List.of(c));
                locked.countDown(); await(release); return active;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var d=pool.submit(()->{ deletion.delete(subject,USER); return true; });
            try { assertThatThrownBy(()->d.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(selection.get(10,TimeUnit.SECONDS)).isTrue(); assertThat(d.get(10,TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(new TransactionTemplate(txManager).<Boolean>execute(tx->sessions.lockActiveMaterialsForSelection(USER,List.of(q),List.of(c)))).isFalse();
    }
    void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    @Test void swaggerDocumentsSubjectDeletion() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}'].delete.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}'].patch").exists());
    }
}
