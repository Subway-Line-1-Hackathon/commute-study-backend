package com.likelion.commute_study_api.tutor;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.support.*;
import com.likelion.commute_study_api.ai.tutor.*;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","SUPABASE_URL=https://auth.example.invalid","material.cleanup-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:tutor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false","spring.data.jpa.repositories.enabled=false","spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:study-read-test-schema.sql,classpath:tutor-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class TutorIntegrationTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111"), OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final AiMeta META=new AiMeta("mock",1,2,10,1);
    @Autowired TutorStreamService service;
    @Autowired TutorContextRepository context;
    @Autowired TutorQuota quota;
    @Autowired AiProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @LocalServerPort int port;
    @MockitoBean TutorAiClient ai;
    @MockitoBean JwtDecoder decoder;
    @TestBean(name="tutorClock",methodName="testClock") Clock clock;
    static final AtomicReference<Instant> NOW=new AtomicReference<>();
    static Clock testClock() {
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
            public Instant instant() { return NOW.get(); }
        };
    }
    long question,foreignQuestion,category,material;
    @BeforeEach void seed() {
        NOW.set(Instant.parse("2026-11-08T14:59:59Z")); properties.setQuestionDailyLimit(20);
        properties.getTimeout().put("ai05",15);
        for(String table:List.of("ai_question_usage","attempt","session_question","session","question","category","material_page","material","subject","app_user")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?),(?)",USER,OTHER);
        question=question(USER); foreignQuestion=question(OTHER);
        category=jdbc.queryForObject("SELECT category_id FROM question WHERE id=?",Long.class,question);
        material=jdbc.queryForObject("SELECT material_id FROM category WHERE id=?",Long.class,category);
        jdbc.update("INSERT INTO material_page VALUES (?,1,'outside'),(?,2,'inside2'),(?,3,'inside3'),(?,4,'outside')",material,material,material,material);
        when(decoder.decode(anyString())).thenAnswer(inv->Jwt.withTokenValue(inv.getArgument(0)).header("alg","RS256")
                .subject(USER.toString()).claim("scope","read").build());
    }
    long question(UUID user) {
        jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'과목')",user); long s=max("subject");
        jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status) VALUES (?,'','PDF','EXPIRED')",s); long m=max("material");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',2,3,1,'AVAILABLE')",s,m); long c=max("category");
        jdbc.update("INSERT INTO question(category_id,type,stem,choices,answer,explanation,origin) VALUES (?,'MC','문제','[\"one\",\"two\",\"three\",\"four\"]','2','해설','PREGEN')",c);
        return max("question");
    }
    long max(String table) { return jdbc.queryForObject("SELECT max(id) FROM "+table,Long.class); }
    AskRequest input() { return new AskRequest("왜 틀렸어?",null); }
    HttpRequest http(long q,String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/questions/"+q+"/ask"))
                .header("Authorization","Bearer local-test-token").header("Accept","text/event-stream")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    HttpResponse<String> askHttp() throws Exception {
        try(var client=HttpClient.newHttpClient()) { return client.send(http(question,"{\"message\":\"설명\"}"),HttpResponse.BodyHandlers.ofString()); }
    }
    void usage(int count) { jdbc.update("INSERT INTO ai_question_usage VALUES (?,?,?)",USER,quota.today(),count); }

    @Test void contextIncludesOnlyOwnedCategoryPagesRecentHistoryAndPreferredOwnSessionAnswer() {
        answer(USER,USER,"COMPLETED","2026-11-08T12:00:00Z","latest-completed");
        answer(USER,USER,"IN_PROGRESS","2026-11-08T10:00:00Z","active");
        answer(OTHER,OTHER,"IN_PROGRESS","2026-11-08T14:00:00Z","foreign");
        answer(USER,OTHER,"IN_PROGRESS","2026-11-08T15:00:00Z","foreign-session");
        var history=new ArrayList<AskRequest.Message>();
        for(int i=0;i<8;i++) history.add(new AskRequest.Message(i%2==0?"user":"assistant","h"+i));
        var request=context.request(question,USER,new AskRequest(" original ",history));
        assertThat(request.message()).isEqualTo(" original ");
        assertThat(request.history()).extracting(TutorAskRequest.HistoryItem::content).containsExactly("h2","h3","h4","h5","h6","h7");
        assertThat(request.category().categoryId()).isEqualTo(category);
        assertThat(request.category().pages()).containsExactly(new AiPage(2,"inside2"),new AiPage(3,"inside3"));
        assertThat(request.question().userAnswer()).isEqualTo("active");
        assertThat(request.question().choices()).containsExactly("one","two","three","four");
        assertThat(request.question().answer()).isEqualTo("2");
        jdbc.update("UPDATE session SET status='COMPLETED'");
        assertThat(context.request(question,USER,input()).question().userAnswer()).isEqualTo("latest-completed");
    }
    void answer(UUID user,UUID sessionUser,String status,String time,String answer) {
        jdbc.update("INSERT INTO session(user_id,mode,status) VALUES (?,'CURRICULUM',?)",sessionUser,status);
        jdbc.update("INSERT INTO attempt(user_id,question_id,session_id,answer,answered_at) VALUES (?,?,?,?,?)",user,question,max("session"),answer,OffsetDateTime.parse(time));
    }
    @Test void absentAnswerAndHistoryAreEmptyAndShortAnswerFallsBackToRepresentative() {
        jdbc.update("UPDATE question SET type='SHORT',answer=NULL,accepted_answers='[\"대표\",\"별칭\"]',choices=NULL,explanation=NULL WHERE id=?",question);
        var request=context.request(question,USER,input());
        assertThat(request.question().userAnswer()).isEmpty(); assertThat(request.history()).isEmpty();
        assertThat(request.question().answer()).isEqualTo("대표"); assertThat(request.question().choices()).isEmpty();
        assertThat(request.question().explanation()).isEmpty();
        jdbc.update("UPDATE question SET type='OX',answer='O' WHERE id=?",question);
        assertThat(context.request(question,USER,input()).question().answer()).isEqualTo("O");
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"message\":\"\"}","{\"message\":\"   \"}","{\"message\":\"ok\",\"history\":[{\"role\":\"system\",\"content\":\"bad\"}]}","{\"message\":\"ok\",\"history\":[null]}"})
    void invalidRequestIsJson400EvenWithSseAccept(String body) throws Exception {
        mvc.perform(post("/api/questions/{id}/ask",question).with(jwt().jwt(j->j.subject(USER.toString())))
                .contentType("application/json").accept("text/event-stream").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(ai);
    }
    @Test void missingForeignAndUnauthenticatedRequestsAreRejectedAndQuotaStartsAtZero() throws Exception {
        for(long id:List.of(foreignQuestion,999999L)) mvc.perform(post("/api/questions/{id}/ask",id).with(jwt().jwt(j->j.subject(USER.toString())))
                .contentType("application/json").accept("text/event-stream").content("{\"message\":\"ok\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/questions/{id}/ask",question).contentType("application/json").content("{\"message\":\"ok\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/ai-quota")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/ai-quota").with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(jsonPath("$.used").value(0)).andExpect(jsonPath("$.remaining").value(20));
    }
    @Test void realHttpFlushesDeltaBeforeDoneAndChargesNineteenToTwentyExactlyOnce() throws Exception {
        usage(19); var release=new CountDownLatch(1);
        doAnswer(inv->{ TutorEventListener l=inv.getArgument(1); l.onDelta("first");
            assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); l.onDone(META); l.onDone(META); return null; }).when(ai).ask(any(),any());
        try(var client=HttpClient.newHttpClient()) {
            var response=client.sendAsync(http(question,"{\"message\":\"ok\"}"),HttpResponse.BodyHandlers.ofInputStream()).get(5,TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-cache");
            assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
            try(var reader=new BufferedReader(new InputStreamReader(response.body()))) {
                assertThat(reader.readLine()).isEqualTo("event: delta");
                assertThat(reader.readLine()).contains("first");
                assertThat(quota.get(USER).used()).isEqualTo(19); release.countDown();
                assertThat(reader.lines().toList().toString()).contains("event: done","mock");
            }
        } finally { release.countDown(); }
        assertThat(quota.get(USER).used()).isEqualTo(20);
        var blocked=askHttp(); assertThat(blocked.statusCode()).isEqualTo(429); assertThat(blocked.body()).contains("AI_QUESTION_LIMIT");
    }
    @Test void upstreamFailuresBeforeAndAfterFirstDeltaNeverChargeAndReleaseLock() throws Exception {
        doThrow(new ApiException(AiErrorCode.AI_UNAVAILABLE)).when(ai).ask(any(),any());
        var before=askHttp(); assertThat(before.statusCode()).isEqualTo(502); assertThat(before.body()).contains("AI_UNAVAILABLE").doesNotContain("event:");
        doAnswer(inv->{ TutorEventListener l=inv.getArgument(1); l.onDelta("partial"); l.onError("LLM_FAILED","모델 오류"); return null; }).when(ai).ask(any(),any());
        var after=askHttp(); assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).contains("partial","event: error","LLM_FAILED","모델 오류").doesNotContain("event: done");
        assertThat(quota.get(USER).used()).isZero();
        doAnswer(inv->{ ((TutorEventListener)inv.getArgument(1)).onDone(META); return null; }).when(ai).ask(any(),any());
        assertThat(askHttp().body()).contains("event: done"); assertThat(quota.get(USER).used()).isEqualTo(1);
    }
    @Test void eofWithoutTerminalEventIsErrorAndDoesNotCharge() throws Exception {
        doAnswer(inv->{ ((TutorEventListener)inv.getArgument(1)).onDelta("partial"); return null; }).when(ai).ask(any(),any());
        assertThat(askHttp().body()).contains("event: error","AI_UNAVAILABLE"); assertThat(quota.get(USER).used()).isZero();
    }
    @Test void sameUserConflictsOtherUsersAreIndependentAndDisconnectIgnoresLateDone() {
        var first=service.prepare(question,USER,input());
        assertThatThrownBy(()->service.prepare(question,USER,input())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.STATE_CONFLICT));
        var other=service.prepare(foreignQuestion,OTHER,input()); other.disconnect(); first.disconnect(); first.onDone(META);
        assertThat(quota.get(USER).used()).isZero(); service.prepare(question,USER,input()).disconnect();
    }
    @Test void downstreamDeltaAndDoneWriteFailuresNeverConsumeQuota() throws Exception {
        for(String failure:List.of("delta","done")) {
            var out=new RecordingOutput(failure);
            doAnswer(inv->{ TutorEventListener l=inv.getArgument(1); l.onDelta("text"); l.onDone(META); return null; }).when(ai).ask(any(),any());
            service.prepare(question,USER,input()).start(out);
            assertThat(out.complete.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(quota.get(USER).used()).isZero(); service.prepare(question,USER,input()).disconnect();
        }
    }
    @Test void dbFailureNeverSendsDoneAndReleasesUser() throws Exception {
        jdbc.execute("ALTER TABLE ai_question_usage ADD CONSTRAINT reject_charge CHECK (count=0)");
        try {
            doAnswer(inv->{ ((TutorEventListener)inv.getArgument(1)).onDone(META); return null; }).when(ai).ask(any(),any());
            var response=askHttp(); assertThat(response.statusCode()).isEqualTo(500); assertThat(response.body()).doesNotContain("event: done");
            assertThat(quota.get(USER).used()).isZero(); service.prepare(question,USER,input()).disconnect();
        } finally { jdbc.execute("ALTER TABLE ai_question_usage DROP CONSTRAINT reject_charge"); }
    }
    @Test void conditionalDatabaseIncrementsNeverExceedLimitUnderConcurrency() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<35;i++) futures.add(pool.submit(()->{ try { quota.charge(USER); return true; } catch(ApiException e) { return false; } }));
            int successes=0; for(var f:futures) if(f.get(15,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(20); assertThat(quota.get(USER).used()).isEqualTo(20);
        }
        assertThat(quota.get(OTHER).used()).isZero();
    }
    @Test void midnightUsesCompletionDayAndConfigurationLimitIsReused() {
        usage(20); assertThat(quota.get(USER).remaining()).isZero();
        NOW.set(Instant.parse("2026-11-08T15:00:00Z"));
        assertThat(quota.get(USER).used()).isZero(); assertThat(quota.today()).isEqualTo(LocalDate.of(2026,11,9));
        properties.setQuestionDailyLimit(1); quota.charge(USER);
        assertThatThrownBy(()->quota.charge(USER)).isInstanceOf(ApiException.class);
        assertThat(quota.get(USER).limit()).isEqualTo(1);
        properties.setQuestionDailyLimit(0); assertThat(quota.get(USER).remaining()).isZero();
    }

    @Test void inFlightQuestionIsChargedOnCompletionDay() throws Exception {
        usage(19);
        var pending=service.prepare(question,USER,input());
        NOW.set(Instant.parse("2026-11-08T15:00:00Z"));
        doAnswer(inv->{ ((TutorEventListener)inv.getArgument(1)).onDone(META); return null; }).when(ai).ask(any(),any());
        var output=new RecordingOutput("none"); pending.start(output);
        assertThat(output.complete.await(5,TimeUnit.SECONDS)).isTrue();
        assertThat(quota.get(USER).used()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count FROM ai_question_usage WHERE user_id=? AND date=?",Integer.class,USER,LocalDate.of(2026,11,8))).isEqualTo(19);
    }

    @Test void rejectedWorkerReleasesOccupancyWithoutCharge() throws Exception {
        var executor=Executors.newVirtualThreadPerTaskExecutor(); executor.close();
        var isolated=new TutorStreamService(context,quota,ai,executor);
        var output=new RecordingOutput("none"); isolated.prepare(question,USER,input()).start(output);
        assertThat(output.complete.await(5,TimeUnit.SECONDS)).isTrue();
        isolated.prepare(question,USER,input()).disconnect(); assertThat(quota.get(USER).used()).isZero();
    }

    @Test void concurrentHttpRequestReturns409WhileFirstQuestionIsRunning() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(inv->{ entered.countDown(); release.await(5,TimeUnit.SECONDS); ((TutorEventListener)inv.getArgument(1)).onDone(META); return null; }).when(ai).ask(any(),any());
        try(var client=HttpClient.newHttpClient()) {
            var first=client.sendAsync(http(question,"{\"message\":\"ok\"}"),HttpResponse.BodyHandlers.ofString());
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=client.send(http(question,"{\"message\":\"ok\"}"),HttpResponse.BodyHandlers.ofString());
            assertThat(second.statusCode()).isEqualTo(409); assertThat(second.body()).contains("STATE_CONFLICT");
            release.countDown(); assertThat(first.get(5,TimeUnit.SECONDS).body()).contains("event: done");
        } finally { release.countDown(); }
        assertThat(quota.get(USER).used()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void servletDeadlineBecomesJsonOrSseTimeoutAndUnblocksUser(boolean delta) throws Exception {
        properties.getTimeout().put("ai05",1);
        doAnswer(inv->{ if(delta) ((TutorEventListener)inv.getArgument(1)).onDelta("partial"); Thread.sleep(10000); return null; }).when(ai).ask(any(),any());
        long start=System.nanoTime(); var result=askHttp();
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(5));
        assertThat(result.statusCode()).isEqualTo(delta?200:504); assertThat(result.body()).contains("AI_TIMEOUT");
        assertThat(result.body().contains("event: error")).isEqualTo(delta);
        assertThat(quota.get(USER).used()).isZero(); service.prepare(question,USER,input()).disconnect();
    }
    @Test void swaggerDocumentsBearerAndSse() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/questions/{id}/ask'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/questions/{id}/ask'].post.responses['200'].content['text/event-stream']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me/ai-quota'].get").exists());
    }
    static class RecordingOutput implements TutorStreamService.Output {
        final String fail; final CountDownLatch complete=new CountDownLatch(1);
        RecordingOutput(String fail) { this.fail=fail; }
        public void event(String name,Object value) throws IOException { if(name.equals(fail)) throw new IOException("disconnected"); }
        public void startupError(ApiException e) {}
        public void complete() { complete.countDown(); }
    }
}
