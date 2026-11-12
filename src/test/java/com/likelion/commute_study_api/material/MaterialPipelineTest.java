package com.likelion.commute_study_api.material;

import java.util.*;
import java.util.concurrent.*;
import com.likelion.commute_study_api.ai.*;
import com.likelion.commute_study_api.ai.material.*;
import com.likelion.commute_study_api.ai.question.*;
import com.likelion.commute_study_api.ai.support.AiPage;
import com.likelion.commute_study_api.common.event.MaterialProcessedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import com.likelion.commute_study_api.question.service.QuestionPregenEventListener;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=", "SUPABASE_URL=https://auth.example.invalid",
        "spring.datasource.url=jdbc:h2:mem:materials;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:db/migration/V4__add_material_file.sql,classpath:db/migration/V5__add_material_lifecycle_tracking.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class MaterialPipelineTest {
    static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaterialTransactions transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean MaterialStorage storage;
    @MockitoBean MaterialAiClient ai;
    @MockitoBean QuestionAiClient questions;
    @MockitoBean JwtDecoder decoder;
    @MockitoSpyBean QuestionPregenEventListener listener;
    long subject;
    CompletableFuture<MaterialProcessResponse> pending;

    @BeforeEach
    void prepare() {
        for (String table : List.of("question", "category", "material_page", "material_file", "material", "subject", "app_user"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?)", USER);
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?, '수학')", USER);
        subject = jdbc.queryForObject("SELECT id FROM subject", Long.class);
        when(storage.reference(any(), anyString())).thenAnswer(inv -> "private/" + inv.getArgument(0) + "/" + UUID.randomUUID() + "." + inv.getArgument(1));
        when(storage.signedUrl(anyString(), anyInt())).thenReturn("https://project.supabase.co/storage/v1/object/sign/private/file?token=SECRET");
        pending = new CompletableFuture<>();
        when(ai.process(any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return pending;
        });
        when(questions.generate(any())).thenAnswer(inv -> CompletableFuture.completedFuture(generated(inv.getArgument(0))));
    }

    @AfterEach
    void finishOutstandingWork() {
        pending.completeExceptionally(new AiCallException("LLM_FAILED", "test cleanup"));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM material WHERE status IN ('PROCESSING','UPLOADED')", Integer.class)).isZero());
    }

    @Test
    void uploadCommitsImmediatelyThenRealSlice9ListenerGeneratesFortyQuestions() throws Exception {
        var questionResult = new CompletableFuture<QuestionGenerateResponse>();
        var captured = new java.util.concurrent.atomic.AtomicReference<QuestionGenerateRequest>();
        doAnswer(inv -> { captured.set(inv.getArgument(0)); return questionResult; }).when(questions).generate(any());
        long id = upload(pdf());
        processing(id);
        assertThat(pending.isDone()).isFalse();
        assertThat(jdbc.queryForObject("SELECT file_url FROM material WHERE id=?", String.class, id)).doesNotContain("http", "token");
        assertThat(jdbc.queryForObject("SELECT expires_at > created_at FROM material WHERE id=?", Boolean.class, id)).isTrue();
        pending.complete(result("- **개념**: 설명 (1쪽)", 1, 2));
        await().atMost(10, TimeUnit.SECONDS).until(() -> captured.get() != null);
        assertThat(state(id)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT question_status FROM category", String.class)).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT summary FROM category", String.class)).isEqualTo("- **개념**: 설명 (1쪽)");
        assertThat(captured.get().category().pages()).containsExactly(new AiPage(1,"쪽1"), new AiPage(2,"쪽2"));
        verify(listener).onMaterialProcessed(new MaterialProcessedEvent(id, subject));
        questionResult.complete(generated(captured.get()));
        available();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question", Integer.class)).isEqualTo(40);
        transactions.complete(id, UUID.randomUUID(), result("중복", 1, 2));
        verify(listener, times(1)).onMaterialProcessed(any());
        mvc.perform(get("/api/materials/{id}/status", id).with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.categoryCount").value(1)).andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void pptxIsMappedToAiPptAndDuplicateQueueEventsDoNotProcessTwice() throws Exception {
        long id = upload(new MockMultipartFile("file", "강의.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", new byte[]{1,2}));
        processing(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> events.publishEvent(new MaterialQueuedEvent(id, false)));
        pending.complete(result("요약", 1, 2));
        available();
        verify(ai, times(1)).process(argThat(request -> request.fileType().equals("PPT") && request.fileName().equals("강의.pptx")));
    }

    @ParameterizedTest
    @ValueSource(strings={"UNSUPPORTED_FILE","PAGE_LIMIT_EXCEEDED","TEXT_EXTRACTION_FAILED","LLM_FAILED","LLM_TIMEOUT"})
    void errorsAreSanitizedAndNeverPublishProcessed(String code) throws Exception {
        long id = upload(pdf()); processing(id);
        Throwable error = code.equals("LLM_TIMEOUT") ? new ApiException(AiErrorCode.AI_TIMEOUT, "SECRET https://bad")
                : code.equals("LLM_FAILED") ? new ApiException(AiErrorCode.AI_UNAVAILABLE, "SECRET")
                : new AiCallException(code,"SECRET https://bad");
        pending.completeExceptionally(error);
        failed(id);
        assertThat(jdbc.queryForObject("SELECT error_message FROM material",String.class)).doesNotContain("SECRET", "http");
        verify(listener, never()).onMaterialProcessed(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category",Integer.class)).isZero();
    }

    @Test
    void fileFetchFailureRefreshesUrlExactlyOnce() throws Exception {
        when(ai.process(any())).thenReturn(CompletableFuture.failedFuture(new AiCallException("FILE_FETCH_FAILED", "secret")));
        long id = upload(pdf()); failed(id);
        verify(ai, times(2)).process(any());
        verify(storage, times(2)).signedUrl(anyString(), eq(600));
        verify(listener, never()).onMaterialProcessed(any());
    }

    @Test
    void fileFetchSecondAttemptCanSucceed() throws Exception {
        when(ai.process(any())).thenReturn(CompletableFuture.failedFuture(new AiCallException("FILE_FETCH_FAILED","secret")),
                CompletableFuture.completedFuture(result("요약",1,2)));
        upload(pdf()); available();
        verify(storage, times(2)).signedUrl(anyString(), eq(600));
        verify(ai, times(2)).process(any());
    }

    @Test
    void emptyAiSuccessBodyBecomesFailedInsteadOfStayingProcessing() throws Exception {
        long id=upload(pdf()); processing(id);
        pending.complete(null); failed(id);
        verify(listener,never()).onMaterialProcessed(any());
    }

    @ParameterizedTest
    @ValueSource(ints={0,3})
    void invalidPageRangeFailsWithoutPartialRows(int start) throws Exception {
        long id = upload(pdf()); processing(id);
        pending.complete(result("요약",start,2)); failed(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material_page",Integer.class)).isZero();
        verify(listener, never()).onMaterialProcessed(any());
    }

    @Test
    void invalidPageEndAndOversizedSummaryAreRejected() {
        var validator = new MaterialResultValidator();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(result("요약",1,3))).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(result("가".repeat(1501),1,2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultDbFailureRollsBackPagesAndReadyAndSuppressesEvent() throws Exception {
        jdbc.execute("ALTER TABLE category ADD CONSTRAINT reject_category CHECK (name <> '단원')");
        try {
            long id=upload(pdf()); processing(id); pending.complete(result("요약",1,2)); failed(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_page",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM category",Integer.class)).isZero();
            verify(listener, never()).onMaterialProcessed(any());
        } finally { jdbc.execute("ALTER TABLE category DROP CONSTRAINT reject_category"); }
    }

    @Test
    void failedCanRetryOnceConcurrentlyAndReadyCannotRetry() throws Exception {
        long id=upload(pdf()); processing(id); pending.completeExceptionally(new AiCallException("LLM_FAILED","failed")); failed(id);
        pending = new CompletableFuture<>();
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> request=() -> mvc.perform(post("/api/materials/{id}/retry", id)
                    .with(jwt().jwt(j -> j.subject(USER.toString())))).andReturn().getResponse().getStatus();
            var responses=pool.invokeAll(List.of(request,request));
            assertThat(List.of(responses.get(0).get(), responses.get(1).get())).containsExactlyInAnyOrder(202,409);
        }
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> verify(ai,times(2)).process(any()));
        pending.complete(result("요약",1,2)); available();
        mvc.perform(post("/api/materials/{id}/retry", id).with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isConflict());
        verify(storage, times(1)).upload(anyString(),any());
    }

    @Test
    void foreignAndMissingMaterialsAndSubjectsReturn404WithoutStorage() throws Exception {
        mvc.perform(multipart("/api/subjects/{id}/materials", subject).file(pdf()).param("kind","LECTURE")
                        .with(jwt().jwt(j -> j.subject(OTHER.toString())))).andExpect(status().isNotFound());
        verify(storage, never()).upload(anyString(),any());
        long id=upload(pdf()); processing(id); pending.completeExceptionally(new AiCallException("LLM_FAILED","failed")); failed(id);
        for(long target: new long[]{id,Long.MAX_VALUE}) {
            mvc.perform(get("/api/materials/{id}/status",target).with(jwt().jwt(j -> j.subject(OTHER.toString())))).andExpect(status().isNotFound());
            mvc.perform(post("/api/materials/{id}/retry",target).with(jwt().jwt(j -> j.subject(OTHER.toString())))).andExpect(status().isNotFound());
        }
    }

    @Test
    void listReturnsOwnMaterialsNewestFirstAndExcludesOtherUsers() throws Exception {
        jdbc.update("INSERT INTO app_user(id) VALUES (?)", OTHER);
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?, '영어')", OTHER);
        long otherSubject = jdbc.queryForObject("SELECT id FROM subject WHERE user_id=?", Long.class, OTHER);
        long older = insertMaterial(subject, "1강.pdf", "READY", "2026-11-07T09:00:00+09:00");
        long newer = insertMaterial(subject, "2강.pptx", "FAILED", "2026-11-08T09:00:00+09:00");
        insertMaterial(otherSubject, "남의자료.pdf", "READY", "2026-11-09T09:00:00+09:00");

        mvc.perform(get("/api/materials").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materials", hasSize(2)))
                .andExpect(jsonPath("$.materials[0].id").value(newer))
                .andExpect(jsonPath("$.materials[0].subjectId").value(subject))
                .andExpect(jsonPath("$.materials[0].fileName").value("2강.pptx"))
                .andExpect(jsonPath("$.materials[0].status").value("FAILED"))
                .andExpect(jsonPath("$.materials[1].id").value(older));
    }

    @Test
    void listRequiresAuthenticationAndReturnsEmptyForUserWithNoMaterials() throws Exception {
        mvc.perform(get("/api/materials")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/materials").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.materials", hasSize(0)));
    }

    long insertMaterial(long subjectId, String fileName, String status, String createdAt) {
        jdbc.update("INSERT INTO material(subject_id,file_url,file_name,file_type,kind,size,status,created_at) VALUES (?,'private/ref.pdf',?,'PDF','LECTURE',1,?,?)",
                subjectId, fileName, status, java.time.OffsetDateTime.parse(createdAt));
        return jdbc.queryForObject("SELECT max(id) FROM material", Long.class);
    }

    @Test
    void storageFailureLeavesNoMaterialAndDbFailureDeletesUploadedObject() throws Exception {
        doThrow(new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE)).when(storage).upload(anyString(),any());
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(pdf()).param("kind","LECTURE")
                        .with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isBadGateway());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
        verify(storage).delete(anyString());
        doNothing().when(storage).upload(anyString(),any());
        jdbc.execute("ALTER TABLE material ADD CONSTRAINT reject_upload CHECK (size < 1)");
        try {
            mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(pdf()).param("kind","LECTURE")
                            .with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isInternalServerError());
            verify(storage,times(2)).delete(anyString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE material DROP CONSTRAINT reject_upload"); }
    }

    @Test
    void authenticationAndMultipartValidationAreEnforced() throws Exception {
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(pdf()).param("kind","LECTURE"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/materials/1/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/materials/1/retry")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(pdf()).with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(multipart("/api/subjects/{id}/materials",subject)
                        .file(new MockMultipartFile("file","bad.ppt","application/vnd.ms-powerpoint",new byte[]{1}))
                        .param("kind","LECTURE").with(jwt())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        verifyNoInteractions(storage);
    }

    @Test
    void oversizedFileReturns413BeforeStorageOrMaterialCreation() throws Exception {
        var oversized=new MockMultipartFile("file","a.pdf","application/pdf",new byte[]{1}) {
            @Override public long getSize() { return MaterialUploadValidator.MAX_BYTES+1; }
        };
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(oversized).param("kind","LECTURE")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        verifyNoInteractions(storage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
    }

    @Test
    void rolledBackQueueEventNeverStartsAi() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            events.publishEvent(new MaterialQueuedEvent(999, false));
            tx.setRollbackOnly();
        });
        verifyNoInteractions(ai);
    }

    @Test
    void expiredFailedMaterialCannotRetry() throws Exception {
        long id=upload(pdf()); processing(id);
        pending.completeExceptionally(new AiCallException("LLM_FAILED","failed")); failed(id);
        jdbc.update("UPDATE material SET expires_at = TIMESTAMP '2000-01-01 00:00:00' WHERE id=?", id);
        mvc.perform(post("/api/materials/{id}/retry",id).with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isConflict());
    }

    long upload(MockMultipartFile file) throws Exception {
        var response=mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(file).param("kind","LECTURE")
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("UPLOADED")).andReturn();
        return new tools.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("materialId").longValue();
    }

    @Test
    void twentyPngsKeepMultipartOrderAndRefreshAllSignedUrlsBeforeSlice9() throws Exception {
        var signingCount=new java.util.concurrent.atomic.AtomicInteger();
        when(storage.signedUrl(anyString(),anyInt())).thenAnswer(inv -> "https://project.supabase.co/storage/v1/object/sign/"
                + inv.getArgument(0) + "?token=" + signingCount.incrementAndGet());
        when(ai.process(any())).thenReturn(CompletableFuture.failedFuture(new AiCallException("FILE_FETCH_FAILED","expired")),pending);
        var files=new ArrayList<MockMultipartFile>();
        for(int i=20;i>=1;i--) files.add(new MockMultipartFile("file","page-"+i+".png","image/png",new byte[]{1,2}));
        long id=uploadBatch(files);
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> verify(ai,times(2)).process(any()));
        var uploads=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storage,times(20)).upload(uploads.capture(),any());
        var ordered=jdbc.queryForList("SELECT file_url FROM material_file WHERE material_id=? ORDER BY page_order",String.class,id);
        assertThat(ordered).containsExactlyElementsOf(uploads.getAllValues());
        assertThat(jdbc.queryForList("SELECT page_order FROM material_file WHERE material_id=? ORDER BY page_order",Integer.class,id))
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,20).boxed().toList());
        assertThat(jdbc.queryForObject("SELECT size FROM material WHERE id=?",Long.class,id)).isEqualTo(40L);
        assertThat(jdbc.queryForObject("SELECT file_url FROM material WHERE id=?",String.class,id)).isEqualTo(ordered.getFirst());
        var requests=org.mockito.ArgumentCaptor.forClass(MaterialProcessRequest.class);
        verify(ai,times(2)).process(requests.capture());
        for(int round=0;round<2;round++) {
            var request=requests.getAllValues().get(round);
            assertThat(request.fileType()).isEqualTo("PNG");
            assertThat(request.fileName()).isEqualTo("page-20.png");
            assertThat(request.fileUrls()).hasSize(20);
            for(int i=0;i<20;i++) assertThat(request.fileUrls().get(i)).endsWith(ordered.get(i)+"?token="+(round*20+i+1));
        }
        pending.complete(new MaterialProcessResponse(20,java.util.stream.IntStream.rangeClosed(1,20)
                .mapToObj(i -> new AiPage(i,"쪽"+i)).toList(),List.of(new MaterialProcessResponse.Category(1,"단원",1,20,"PNG 요약")),null));
        available();
        verify(listener).onMaterialProcessed(new MaterialProcessedEvent(id,subject));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM question",Integer.class)).isEqualTo(40);
    }

    @Test
    void pngFailedRetryReusesAllOriginalObjects() throws Exception {
        long id=uploadBatch(List.of(png("second.png"),png("first.png")));
        processing(id); pending.completeExceptionally(new AiCallException("TEXT_EXTRACTION_FAILED","failed")); failed(id);
        var before=jdbc.queryForList("SELECT file_url FROM material_file WHERE material_id=? ORDER BY page_order",String.class,id);
        pending=new CompletableFuture<>();
        mvc.perform(post("/api/materials/{id}/retry",id).with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isAccepted());
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> verify(ai,times(2)).process(any()));
        verify(storage,times(2)).upload(anyString(),any());
        for(String ref:before) verify(storage,times(2)).signedUrl(eq(ref),eq(600));
        pending.complete(result("요약",1,2)); available();
        assertThat(jdbc.queryForList("SELECT file_url FROM material_file WHERE material_id=? ORDER BY page_order",String.class,id))
                .isEqualTo(before);
    }

    @Test
    void invalidLastPngAndTwentyOnePartsNeverReachStorage() throws Exception {
        var invalid=new MockMultipartFile("file","bad.png","text/plain",new byte[]{1});
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(png("ok.png")).file(invalid)
                        .param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        var request=multipart("/api/subjects/{id}/materials",subject);
        for(int i=0;i<21;i++) request.file(png("p"+i+".png"));
        mvc.perform(request.param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(storage);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
    }

    @Test
    void partialPngUploadFailureCleansEveryAttemptedObjectAndContinuesAfterDeleteFailure() throws Exception {
        doNothing().doThrow(new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE)).when(storage).upload(anyString(),any());
        doThrow(new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE)).doNothing().when(storage).delete(anyString());
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(png("1.png")).file(png("2.png")).file(png("3.png"))
                        .param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadGateway());
        var uploads=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(storage,times(2)).upload(uploads.capture(),any());
        for(String ref:uploads.getAllValues()) verify(storage).delete(ref);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
        verifyNoInteractions(ai);
    }

    @Test
    void pngFileRowInsertFailureRollsBackMaterialAndDeletesAllStorageObjects() throws Exception {
        jdbc.execute("ALTER TABLE material_file ADD CONSTRAINT reject_second CHECK (page_order <> 2)");
        try {
            mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(png("1.png")).file(png("2.png"))
                            .param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                    .andExpect(status().isInternalServerError());
            verify(storage,times(2)).delete(anyString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM material_file",Integer.class)).isZero();
            verifyNoInteractions(ai);
        } finally { jdbc.execute("ALTER TABLE material_file DROP CONSTRAINT reject_second"); }
    }

    MockMultipartFile png(String name) { return new MockMultipartFile("file",name,"image/png",new byte[]{1,2}); }
    MockMultipartFile jpg(String name) { return new MockMultipartFile("file",name,"image/jpeg",new byte[]{1,2}); }

    /** #66: JPG도 PNG처럼 장별 경로를 저장하고 모든 장을 AI에 보낸다(첫 장만 가는 회귀 방지). */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter='|', value={
            "a.jpg|JPG|jpg",
            "a.jpg,b.jpeg|JPG|jpg,jpg",
            "a.jpg,b.png,c.JPG|PNG|jpg,png,jpg"})
    void jpgAndMixedImagesKeepEveryPageInOrder(String names,String expectedType,String extensions) throws Exception {
        var files=new ArrayList<MockMultipartFile>();
        for(String name:names.split(",")) files.add(name.endsWith(".png") ? png(name) : jpg(name));
        long id=uploadBatch(files);
        await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> verify(ai).process(any()));

        assertThat(jdbc.queryForObject("SELECT file_type FROM material WHERE id=?",String.class,id)).isEqualTo(expectedType);
        var ordered=jdbc.queryForList("SELECT file_url FROM material_file WHERE material_id=? ORDER BY page_order",String.class,id);
        assertThat(ordered).hasSize(files.size());
        assertThat(ordered.stream().map(ref -> ref.substring(ref.lastIndexOf('.')+1)).toList())
                .containsExactly(extensions.split(","));
        var uploaded=org.mockito.ArgumentCaptor.forClass(MaterialUploadValidator.ValidatedFile.class);
        verify(storage,times(files.size())).upload(anyString(),uploaded.capture());
        assertThat(uploaded.getAllValues()).allSatisfy(file ->
                assertThat(file.contentType()).isIn("image/jpeg","image/png"));

        var request=org.mockito.ArgumentCaptor.forClass(MaterialProcessRequest.class);
        verify(ai).process(request.capture());
        assertThat(request.getValue().fileType()).isEqualTo(expectedType);
        assertThat(request.getValue().fileName()).isEqualTo(files.getFirst().getOriginalFilename());
        assertThat(request.getValue().fileUrls()).hasSize(files.size());
    }

    @Test
    void jpgWithMismatchedMimeOrMixedWithPdfIsRejectedBeforeStorage() throws Exception {
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(new MockMultipartFile("file","a.jpg","image/png",new byte[]{1}))
                        .param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
        mvc.perform(multipart("/api/subjects/{id}/materials",subject).file(jpg("a.jpg")).file(pdf())
                        .param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(storage);
    }
    long uploadBatch(List<MockMultipartFile> files) throws Exception {
        var request=multipart("/api/subjects/{id}/materials",subject);
        files.forEach(request::file);
        var response=mvc.perform(request.param("kind","LECTURE").with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isCreated()).andReturn();
        return new tools.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString()).get("materialId").longValue();
    }
    MockMultipartFile pdf() { return new MockMultipartFile("file","강의.pdf","application/pdf","%PDF-1.7".getBytes()); }
    void processing(long id) { await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> { assertThat(state(id)).isEqualTo("PROCESSING"); verify(ai,atLeastOnce()).process(any()); }); }
    void failed(long id) { await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> assertThat(state(id)).isEqualTo("FAILED")); }
    void available() { await().atMost(10,TimeUnit.SECONDS).untilAsserted(() -> assertThat(jdbc.queryForObject("SELECT count(*) FROM category WHERE question_status='AVAILABLE'",Integer.class)).isEqualTo(1)); }
    String state(long id) { return jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id); }
    static MaterialProcessResponse result(String summary,int start,int end) { return new MaterialProcessResponse(2,List.of(new AiPage(1,"쪽1"),new AiPage(2,"쪽2")),List.of(new MaterialProcessResponse.Category(1,"단원",start,end,summary)),null); }
    static QuestionGenerateResponse generated(QuestionGenerateRequest request) {
        var items=new ArrayList<QuestionGenerateResponse.GeneratedQuestion>();
        for(var type:request.requests()) for(int i=0;i<type.count();i++) items.add(new QuestionGenerateResponse.GeneratedQuestion(
                type.type(),type.difficulty(),type.type()+i,List.of("A","B"),"A",List.of("A"),List.of(),"설명",1,"쪽1",List.of("개념")));
        return new QuestionGenerateResponse(items,List.of(),new QuestionGenerateResponse.Rejected(0,0,0),null);
    }
}
