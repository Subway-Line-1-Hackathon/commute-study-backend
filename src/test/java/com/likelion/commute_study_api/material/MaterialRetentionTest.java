package com.likelion.commute_study_api.material;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import com.likelion.commute_study_api.question.service.QuestionPregenEventListener;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=", "material.cleanup-enabled=false", "SUPABASE_URL=https://auth.example.invalid",
        "spring.datasource.url=jdbc:h2:mem:retention;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:db/migration/V4__add_material_file.sql,classpath:db/migration/V5__add_material_lifecycle_tracking.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class MaterialRetentionTest {
    static final Instant NOW=Instant.parse("2026-11-08T03:00:00Z");
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MaterialRetentionService service;
    @Autowired MaterialRetentionTransactions retention;
    @Autowired MaterialTransactions processing;
    @Autowired MaterialRepository materials;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean MaterialStorage storage;
    @MockitoBean MaterialProcessor processor;
    @MockitoBean QuestionPregenEventListener questions;
    @MockitoBean JwtDecoder decoder;
    @TestBean(name="materialRetentionClock", methodName="clock") Clock clock;
    static Clock clock() { return Clock.fixed(NOW,ZoneOffset.UTC); }

    @BeforeEach void resetDatabase() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS attempt (id BIGINT PRIMARY KEY, question_id BIGINT REFERENCES question(id), answer TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS wrong_note (question_id BIGINT REFERENCES question(id), user_id UUID, wrong_count INT)");
        for(String table:List.of("attempt","wrong_note","question","category","material_page","material_file","material","subject","app_user")) jdbc.update("DELETE FROM "+table);
        for(UUID user:List.of(USER,OTHER)) jdbc.update("INSERT INTO app_user(id) VALUES (?)",user);
        doAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(storage).delete(anyString());
    }

    @ParameterizedTest @ValueSource(strings={"PDF","PPT"})
    void sevenDayExpiryClearsOnlyReferencesAndPreservesAllLearningData(String type) throws Exception {
        long id=add(USER,"READY",8,type);
        jdbc.update("INSERT INTO material_page VALUES (?,1,'원문')",id);
        long subject=jdbc.queryForObject("SELECT subject_id FROM material WHERE id=?",Long.class,id);
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,summary,\"order\",question_status) VALUES (?,?,'단원',1,1,'**요약**',1,'AVAILABLE')",subject,id);
        long category=jdbc.queryForObject("SELECT id FROM category",Long.class);
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem) VALUES (?,'OX',2,'문제')",category);
        long question=jdbc.queryForObject("SELECT id FROM question",Long.class);
        jdbc.update("INSERT INTO attempt VALUES (1,?,'답안')",question);
        jdbc.update("INSERT INTO wrong_note VALUES (?, ?, 2)",question,USER);
        var before=new HashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("category","material_page","question","attempt","wrong_note")) before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        assertThat(service.cleanUser(USER).expired()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT file_url FROM material WHERE id=?",String.class,id)).isEmpty();
        before.forEach((table,rows) -> assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(rows));
        verify(storage).delete(ref(id));
        mvc.perform(post("/api/materials/{id}/retry",id).with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test void onlyEligibleStatesAreExpiredAndExplicitExpiryOrCreationFallbackIsUsed() {
        long ready=add(USER,"READY",7,"PDF"), failed=add(USER,"FAILED",9,"PDF");
        long recent=add(USER,"READY",6,"PDF"), uploaded=add(USER,"UPLOADED",20,"PDF"), running=add(USER,"PROCESSING",20,"PDF"), expired=add(USER,"EXPIRED",20,"PDF");
        jdbc.update("UPDATE material SET expires_at=NULL WHERE id=?",failed);
        long custom=add(USER,"READY",20,"PDF");
        jdbc.update("UPDATE material SET expires_at=? WHERE id=?",Timestamp.from(NOW.plusSeconds(86400)),custom);
        assertThat(service.cleanUser(USER).expired()).isEqualTo(2);
        assertThat(state(ready)).isEqualTo("EXPIRED"); assertThat(state(failed)).isEqualTo("EXPIRED");
        assertThat(state(recent)).isEqualTo("READY"); assertThat(state(custom)).isEqualTo("READY");
        assertThat(state(uploaded)).isEqualTo("UPLOADED"); assertThat(state(running)).isEqualTo("PROCESSING");
        verify(storage,never()).delete(ref(expired)); verify(storage,never()).delete(ref(running)); verify(storage,never()).delete(ref(uploaded));
    }

    @Test void capacityAggregatesAllSubjectsButNeverOtherUsersOrProcessing() {
        var ids=new ArrayList<Long>();
        for(int i=0;i<11;i++) { long id=add(USER,i%2==0?"READY":"FAILED",0,"PDF"); ids.add(id); jdbc.update("UPDATE material SET created_at=? WHERE id=?",Timestamp.from(NOW.minusSeconds(100-i)),id); }
        long other=add(OTHER,"READY",20,"PDF");
        long processingId=add(USER,"PROCESSING",30,"PDF"), uploaded=add(USER,"UPLOADED",30,"PDF");
        assertThat(service.cleanUser(USER).expired()).isEqualTo(1);
        assertThat(state(ids.getFirst())).isEqualTo("EXPIRED"); assertThat(state(other)).isEqualTo("READY");
        verify(storage,never()).delete(ref(processingId)); verify(storage,never()).delete(ref(uploaded)); verify(storage,never()).delete(ref(other));
        assertThat(service.cleanUser(USER).expired()).isZero();
    }

    @Test void pngPartialFailureSurvivesRestartAndRetryIsBlockedUntilCleanupCompletes() throws Exception {
        long id=add(USER,"FAILED",8,"PNG");
        String first=ref(id), second="private/"+USER+"/"+UUID.randomUUID()+".png";
        jdbc.update("INSERT INTO material_file VALUES (?,1,?)",id,first); jdbc.update("INSERT INTO material_file VALUES (?,2,?)",id,second);
        var deleted=new HashSet<String>(); var shouldFail=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(inv -> {
            String key=inv.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(key.equals(second) && shouldFail.get()) throw new IllegalStateException("secret must not be logged");
            deleted.add(key); return null; // Already missing objects are successful (storage adapter contract).
        }).when(storage).delete(anyString());
        assertThat(service.cleanUser(USER).failed()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("FAILED"); assertThat(deleted).containsExactly(first);
        assertThat(jdbc.queryForObject("SELECT file_url FROM material WHERE id=?",String.class,id)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT cleanup_pending FROM material WHERE id=?",Boolean.class,id)).isTrue();
        mvc.perform(post("/api/materials/{id}/retry",id).with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isConflict());
        shouldFail.set(false);
        // A new service has no memory of the first attempt; the durable DB reservation is sufficient.
        var fresh=new MaterialRetentionService(new MaterialRetentionRepository(jdbc),retention,storage,
                new com.likelion.commute_study_api.common.config.MaterialProperties(),clock);
        assertThat(fresh.cleanUser(USER).expired()).isEqualTo(1); assertThat(deleted).containsExactlyInAnyOrder(first,second);
        assertThat(jdbc.queryForList("SELECT file_url FROM material_file WHERE material_id=?",String.class,id)).containsExactly("","");
        verify(storage,times(2)).delete(first);
    }

    @Test void storageFailureKeepsStateAndOldestSelectionAndNextRunRetries() {
        long oldest=add(USER,"READY",1,"PDF");
        for(int i=0;i<10;i++) add(USER,"READY",0,"PDF");
        doThrow(new IllegalStateException()).when(storage).delete(ref(oldest));
        assertThat(service.cleanUser(USER).failed()).isEqualTo(1); assertThat(state(oldest)).isEqualTo("READY");
        verify(storage,times(1)).delete(anyString());
        doNothing().when(storage).delete(ref(oldest));
        assertThat(service.cleanUser(USER).expired()).isEqualTo(1);
    }

    @Test void recoveryUsesProcessingStartAndOldCallbacksCannotOverwriteNewRetry() throws Exception {
        long stale=add(USER,"PROCESSING",0,"PDF"), active=add(USER,"PROCESSING",30,"PDF");
        UUID old=UUID.randomUUID();
        jdbc.update("UPDATE material SET processing_started_at=?,processing_token=? WHERE id=?",Timestamp.from(NOW.minusSeconds(601)),old,stale);
        jdbc.update("UPDATE material SET processing_started_at=?,processing_token=? WHERE id=?",Timestamp.from(NOW.minusSeconds(60)),UUID.randomUUID(),active);
        assertThat(service.recoverStalled()).isEqualTo(1); assertThat(state(stale)).isEqualTo("FAILED"); assertThat(state(active)).isEqualTo("PROCESSING");
        mvc.perform(post("/api/materials/{id}/retry",stale).with(jwt().jwt(j -> j.subject(USER.toString())))).andExpect(status().isAccepted());
        UUID current=materials.processingToken(stale); assertThat(current).isNotNull().isNotEqualTo(old);
        processing.complete(stale,old,MaterialPipelineTest.result("stale",1,2)); processing.failed(stale,old,"stale failure");
        assertThat(state(stale)).isEqualTo("PROCESSING"); assertThat(materials.processingToken(stale)).isEqualTo(current);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category",Integer.class)).isZero();
        processing.complete(stale,current,MaterialPipelineTest.result("new",1,2));
        assertThat(state(stale)).isEqualTo("READY");
    }

    @Test void recoveryStartsAtTenMinutesAndIsIdempotent() {
        long boundary=add(USER,"PROCESSING",0,"PDF"), fresh=add(USER,"PROCESSING",0,"PDF");
        jdbc.update("UPDATE material SET processing_started_at=? WHERE id=?",Timestamp.from(NOW.minusSeconds(600)),boundary);
        jdbc.update("UPDATE material SET processing_started_at=? WHERE id=?",Timestamp.from(NOW.minusSeconds(599)),fresh);
        assertThat(service.recoverStalled()).isEqualTo(1);
        assertThat(state(boundary)).isEqualTo("FAILED"); assertThat(state(fresh)).isEqualTo("PROCESSING");
        assertThat(service.recoverStalled()).isZero();
    }

    @Test void reservePreventsConcurrentRetryAndDuplicateCleanupIsHarmless() throws Exception {
        long id=add(USER,"FAILED",8,"PDF");
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(inv -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return null; }).when(storage).delete(ref(id));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> service.cleanUser(USER));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> processing.retry(id,USER)).isInstanceOf(com.likelion.commute_study_api.common.exception.ApiException.class);
            var duplicate=executor.submit(() -> service.cleanUser(USER)); release.countDown();
            assertThat(first.get(5,TimeUnit.SECONDS).expired()+duplicate.get(5,TimeUnit.SECONDS).expired()).isEqualTo(1);
        } finally { release.countDown(); }
        verify(storage,times(1)).delete(ref(id)); assertThat(state(id)).isEqualTo("EXPIRED");
    }

    @Test void retryThatWinsBeforeReservationProtectsOriginalFilesAndForeignOwnerCannotReserve() {
        long id=add(USER,"FAILED",0,"PDF");
        assertThat(retention.reserve(id,OTHER)).isEmpty();
        assertThat(materials.claim(id,"FAILED",UUID.randomUUID())).isTrue();
        assertThat(retention.reserve(id,USER)).isEmpty();
        assertThat(service.cleanUser(USER).expired()).isZero(); verifyNoInteractions(storage);
    }

    @Test void dbFinalizeFailureLeavesDurableIntentForNextRun() {
        long id=add(USER,"READY",8,"PDF");
        jdbc.execute("ALTER TABLE material ADD CONSTRAINT prevent_expire CHECK (status <> 'EXPIRED')");
        try { assertThat(service.cleanUser(USER).failed()).isEqualTo(1); assertThat(state(id)).isEqualTo("READY"); }
        finally { jdbc.execute("ALTER TABLE material DROP CONSTRAINT prevent_expire"); }
        assertThat(service.cleanUser(USER).expired()).isEqualTo(1); verify(storage,times(2)).delete(ref(id));
    }

    long add(UUID user,String status,int ageDays,String type) {
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?, '과목')",user);
        long subject=jdbc.queryForObject("SELECT max(id) FROM subject",Long.class);
        String path="private/"+user+"/"+UUID.randomUUID()+"."+(type.equals("PNG")?"png":type.equals("PPT")?"pptx":"pdf");
        jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status,created_at,expires_at) VALUES (?,?,?,?,?,?)",subject,path,type,status,
                Timestamp.from(NOW.minus(Duration.ofDays(ageDays))),Timestamp.from(NOW.plus(Duration.ofDays(7-ageDays))));
        long id=jdbc.queryForObject("SELECT max(id) FROM material",Long.class); refs.put(id,path); return id;
    }
    final Map<Long,String> refs=new HashMap<>();
    String ref(long id) { return refs.get(id); }
    String state(long id) { return jdbc.queryForObject("SELECT status FROM material WHERE id=?",String.class,id); }
}
