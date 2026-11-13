package com.likelion.commute_study_api.report;

import java.time.*;
import java.util.*;
import com.likelion.commute_study_api.common.config.ReportProperties;
import com.likelion.commute_study_api.ai.feedback.FeedbackAiClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","SUPABASE_URL=https://auth.example.invalid","material.cleanup-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:report;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false","spring.data.jpa.repositories.enabled=false","spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:study-read-test-schema.sql,classpath:report-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class ReportApiTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111"),OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportProperties properties;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean FeedbackAiClient ai;
    long subject,secondSubject,foreignSubject,category,question;

    @BeforeEach void setup() {
        properties.setMinAnswers(5);
        for(String table:List.of("attempt","session_question","session","question","category","material_page","material","subject","app_user")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?),(?)",USER,OTHER);
        subject=subject(USER); secondSubject=subject(USER); foreignSubject=subject(OTHER);
        category=category(subject); question=question(category);
    }
    @Test void sessionUsesActualResponsesSelfJudgmentAndLastAnswerNotEndedAt() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","CURRICULUM");
        jdbc.update("UPDATE session SET ended_at=?,feedback='복습하세요' WHERE id=?",time("2026-11-08T01:00:00Z"),s);
        answer(s,question,USER,true,true,"2026-11-08T00:01:00Z");
        answer(s,question(category),USER,false,false,"2026-11-08T00:02:00Z");
        answer(s,question(category),USER,null,false,"2026-11-08T00:24:59Z");
        report(subject,"scope=session&sessionId="+s).andExpect(status().isOk())
                .andExpect(jsonPath("$.today.minutes").value(24)).andExpect(jsonPath("$.today.solved").value(3))
                .andExpect(jsonPath("$.today.wrong").value(1)).andExpect(jsonPath("$.today.accuracy").value(0.5))
                .andExpect(jsonPath("$.feedback").value("복습하세요"));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"2026-11-08T01:00:00Z","2026-11-08T00:00:31Z"})
    void missingNegativeAndSubMinuteDurationIsZero(String started) throws Exception {
        long s=session(USER,started,"CURRICULUM"); answer(s,question,USER,true,false,"2026-11-08T00:01:00Z");
        report(subject,"scope=session&sessionId="+s).andExpect(jsonPath("$.today.minutes").value(0));
    }
    @Test void noAnswersAndFeedbackIsSuccessfulWithNullAccuracy() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","CURRICULUM"); member(s,question);
        report(subject,"scope=session&sessionId="+s).andExpect(status().isOk())
                .andExpect(jsonPath("$.today.minutes").value(0)).andExpect(jsonPath("$.today.solved").value(0))
                .andExpect(jsonPath("$.today.wrong").value(0)).andExpect(jsonPath("$.today.accuracy").value(nullValue()))
                .andExpect(jsonPath("$.feedback").value(nullValue()));
    }
    @Test void duplicateDeliveryCannotInflateSolvedAndPendingIsNotWrong() throws Exception {
        long s=session(USER,null,"CURRICULUM"); answer(s,question,USER,null,false,"2026-11-08T00:00:00Z");
        assertThatThrownBy(()->jdbc.update("INSERT INTO attempt(user_id,question_id,session_id,correct) VALUES (?,?,?,true)",USER,question,s))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        report(subject,"scope=session&sessionId="+s).andExpect(jsonPath("$.today.solved").value(1))
                .andExpect(jsonPath("$.today.accuracy").value(nullValue())).andExpect(jsonPath("$.today.wrong").value(0));
    }
    @Test void missingForeignAndUnrelatedSessionsAre404() throws Exception {
        long owned=session(USER,null,"CURRICULUM"); member(owned,question);
        long foreign=session(OTHER,null,"CURRICULUM"); member(foreign,question);
        long unrelated=session(USER,null,"REVIEW"); member(unrelated,question(category(secondSubject)));
        for(long s:List.of(foreign,unrelated,999999L)) report(subject,"scope=session&sessionId="+s).andExpect(status().isNotFound());
        for(long sub:List.of(foreignSubject,999999L)) {
            report(sub,"scope=all").andExpect(status().isNotFound());
            report(sub,"scope=session&sessionId="+owned).andExpect(status().isNotFound());
        }
    }
    @Test void mixedSubjectReviewSessionSeparatesAnswersAndTime() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","REVIEW");
        answer(s,question,USER,true,false,"2026-11-08T00:05:59Z");
        answer(s,question(category(secondSubject)),USER,false,false,"2026-11-08T00:35:00Z");
        report(subject,"scope=session&sessionId="+s).andExpect(jsonPath("$.today.solved").value(1))
                .andExpect(jsonPath("$.today.minutes").value(5)).andExpect(jsonPath("$.today.accuracy").value(1.0));
        report(secondSubject,"scope=session&sessionId="+s).andExpect(jsonPath("$.today.solved").value(1))
                .andExpect(jsonPath("$.today.minutes").value(35)).andExpect(jsonPath("$.today.wrong").value(1));
    }
    @Test void sessionEndpointAggregatesEverySubjectAndPreservesLegacyTodayFields() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","REVIEW");
        jdbc.update("UPDATE session SET ended_at=? WHERE id=?",time("2026-11-08T02:00:00Z"),s);
        answer(s,question,USER,true,true,"2026-11-08T00:01:00Z");
        answer(s,question(category(secondSubject)),USER,false,false,"2026-11-08T00:10:00Z");
        answer(s,question(category),USER,true,false,"2026-11-08T00:15:00Z");
        answer(s,question(category),USER,null,false,"2026-11-08T00:24:59Z");

        sessionReport(s).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(s))
                .andExpect(jsonPath("$.today.answered").value(4))
                .andExpect(jsonPath("$.today.correct").value(2))
                .andExpect(jsonPath("$.today.accuracy",closeTo(2.0/3,0.000001)))
                .andExpect(jsonPath("$.today.minutes").value(24))
                .andExpect(jsonPath("$.today.solved").value(4))
                .andExpect(jsonPath("$.today.wrong").value(1));
        // The old endpoint remains filtered to its subject and keeps its original response shape.
        report(subject,"scope=session&sessionId="+s).andExpect(status().isOk())
                .andExpect(jsonPath("$",aMapWithSize(2)))
                .andExpect(jsonPath("$.today",aMapWithSize(4)))
                .andExpect(jsonPath("$.today.solved").value(3))
                .andExpect(jsonPath("$.today.wrong").value(0))
                .andExpect(jsonPath("$.today.accuracy").value(1.0))
                .andExpect(jsonPath("$.today.answered").doesNotExist())
                .andExpect(jsonPath("$.feedbackStatus").doesNotExist());
    }
    @Test void sessionEndpointExcludesOtherUsersOtherSessionsAndUnlinkedAttempts() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","CURRICULUM");
        answer(s,question,USER,true,true,"2026-11-08T00:01:00Z");
        answer(s,question(category),OTHER,false,false,"2026-11-08T01:00:00Z");
        jdbc.update("INSERT INTO attempt(user_id,session_id,question_id,correct) VALUES (?,?,?,false)",
                USER,s,question(category));
        answer(session(USER,null,"REVIEW"),question(category(secondSubject)),USER,false,false,"2026-11-08T02:00:00Z");
        answer(session(OTHER,null,"REVIEW"),question,USER,false,false,"2026-11-08T03:00:00Z");

        sessionReport(s).andExpect(status().isOk())
                .andExpect(jsonPath("$.today.answered").value(1))
                .andExpect(jsonPath("$.today.correct").value(1))
                .andExpect(jsonPath("$.today.accuracy").value(1.0))
                .andExpect(jsonPath("$.today.minutes").value(1))
                .andExpect(jsonPath("$.today.wrong").value(0));
    }
    @Test void sessionEndpointSupportsEmptySessionsAndPendingJudgments() throws Exception {
        long s=session(USER,null,"CURRICULUM");
        // A session does not need a subject membership or any answers to be owned and readable.
        sessionReport(s).andExpect(status().isOk())
                .andExpect(jsonPath("$.today.answered").value(0))
                .andExpect(jsonPath("$.today.correct").value(0))
                .andExpect(jsonPath("$.today.accuracy").value(nullValue()))
                .andExpect(jsonPath("$.today.minutes").value(0))
                .andExpect(jsonPath("$.feedback").value(nullValue()))
                .andExpect(jsonPath("$.feedbackStatus").value("PENDING"));
        answer(s,question,USER,null,false,"2026-11-08T00:01:00Z");
        sessionReport(s).andExpect(jsonPath("$.today.answered").value(1))
                .andExpect(jsonPath("$.today.correct").value(0))
                .andExpect(jsonPath("$.today.accuracy").value(nullValue()))
                .andExpect(jsonPath("$.today.wrong").value(0));
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","READY","SKIPPED","FAILED"})
    void sessionEndpointReadsPersistedFeedbackStatus(String feedbackStatus) throws Exception {
        long s=session(USER,null,"CURRICULUM");
        String feedback="READY".equals(feedbackStatus)?"저장된 피드백":null;
        jdbc.update("UPDATE session SET feedback_status=?,feedback=? WHERE id=?",feedbackStatus,feedback,s);

        sessionReport(s).andExpect(status().isOk())
                .andExpect(jsonPath("$.feedbackStatus").value(feedbackStatus))
                .andExpect(jsonPath("$.feedback").value(feedback==null?nullValue():is(feedback)));
        verifyNoInteractions(ai);
    }
    @ParameterizedTest @ValueSource(strings={"READY","IN_PROGRESS","COMPLETED","ENDED"})
    void sessionEndpointCanReadEveryOwnedSessionLifecycleState(String sessionStatus) throws Exception {
        long s=session(USER,null,"CURRICULUM");
        jdbc.update("UPDATE session SET status=? WHERE id=?",sessionStatus,s);
        sessionReport(s).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(s));
    }
    @Test void sessionEndpointRequiresAuthenticationAndHidesMissingOrForeignSessions() throws Exception {
        long owned=session(USER,null,"CURRICULUM"),foreign=session(OTHER,null,"CURRICULUM");
        mvc.perform(get("/api/sessions/{id}/report",owned)).andExpect(status().isUnauthorized());
        for(long s:List.of(foreign,999999L,0L,-1L)) {
            sessionReport(s).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        mvc.perform(get("/api/sessions/{id}/report",owned)
                .with(jwt().jwt(j->j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }
    @Test void trendExcludesFourIncludesFiveAndSixAndAverageUsesAllAnswers() throws Exception {
        repeat(question,4,4,"2026-11-06T00:00:00Z");
        repeat(question,5,1,"2026-11-07T00:00:00Z");
        repeat(question,6,3,"2026-11-08T00:00:00Z");
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend",hasSize(2)))
                .andExpect(jsonPath("$.accuracyTrend[0].date").value("2026-11-07"))
                .andExpect(jsonPath("$.accuracyTrend[0].answers").value(5)).andExpect(jsonPath("$.accuracyTrend[0].accuracy").value(0.2))
                .andExpect(jsonPath("$.accuracyTrend[1].answers").value(6)).andExpect(jsonPath("$.accuracyTrend[1].accuracy").value(0.5))
                .andExpect(jsonPath("$.average",closeTo(8.0/15,0.000001)));
        properties.setMinAnswers(3);
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend",hasSize(3)));
    }
    @Test void kstMidnightUsesNamedZoneEvenWhenDbSessionIsUtc() throws Exception {
        repeat(question,5,5,"2026-11-08T14:59:59Z");
        repeat(question,5,0,"2026-11-08T15:00:00Z");
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend[*].date",contains("2026-11-08","2026-11-09")))
                .andExpect(jsonPath("$.accuracyTrend[*].accuracy",contains(1.0,0.0)));
    }
    @Test void pendingCountsTowardAnswersAndThresholdButNotAccuracyDenominator() throws Exception {
        repeat(question,4,2,"2026-11-08T00:00:00Z");
        answer(session(USER,null,"REVIEW"),question,USER,null,false,"2026-11-08T00:00:00Z");
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend[0].answers").value(5))
                .andExpect(jsonPath("$.accuracyTrend[0].accuracy").value(0.5)).andExpect(jsonPath("$.average").value(0.5));
        jdbc.update("UPDATE attempt SET correct=NULL");
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend[0].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.byCategory[0].accuracy").value(nullValue())).andExpect(jsonPath("$.average").value(nullValue()));
    }
    @Test void categoriesSortWeakestThenIdWithUnstudiedLastAndAverageIsWeighted() throws Exception {
        long weak=category(subject),tie=category(subject),unlearned=category(subject);
        repeat(question,8,6,"2026-11-08T00:00:00Z");
        repeat(question(weak),2,1,"2026-11-08T00:00:00Z");
        repeat(question(tie),2,1,"2026-11-08T00:00:00Z");
        report(subject,"scope=all").andExpect(jsonPath("$.byCategory[*].id",contains((int)weak,(int)tie,(int)category,(int)unlearned)))
                .andExpect(jsonPath("$.byCategory[3].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.average",closeTo(8.0/12,0.000001)));
    }
    @Test void noCategoryAndNoAnswersYieldEmptyArraysAndNullAverage() throws Exception {
        report(secondSubject,"scope=all").andExpect(status().isOk()).andExpect(jsonPath("$.accuracyTrend",hasSize(0)))
                .andExpect(jsonPath("$.byCategory",hasSize(0))).andExpect(jsonPath("$.average").value(nullValue()));
        report(subject,"scope=all").andExpect(jsonPath("$.byCategory[0].accuracy").value(nullValue()));
    }
    @Test void otherUsersForeignSessionAndUnlinkedAttemptsAreExcluded() throws Exception {
        long s=session(USER,null,"CURRICULUM"); answer(s,question,USER,true,false,"2026-11-08T00:00:00Z");
        long q2=question(category); answer(s,q2,OTHER,false,false,"2026-11-08T01:00:00Z");
        answer(session(OTHER,null,"REVIEW"),question,USER,false,false,"2026-11-08T01:00:00Z");
        long q3=question(category);
        jdbc.update("INSERT INTO attempt(user_id,session_id,question_id,correct) VALUES (?,?,?,false)",USER,s,q3);
        repeat(question(category(foreignSubject)),8,0,"2026-11-08T00:00:00Z");
        properties.setMinAnswers(1);
        report(subject,"scope=all").andExpect(jsonPath("$.accuracyTrend[0].answers").value(1)).andExpect(jsonPath("$.average").value(1.0));
        report(subject,"scope=session&sessionId="+s).andExpect(jsonPath("$.today.solved").value(1)).andExpect(jsonPath("$.today.accuracy").value(1.0));
    }
    @ParameterizedTest @ValueSource(strings={"","scope=bad","scope=SESSION","scope=session","scope=session&sessionId=0","scope=session&sessionId=-1","scope=session&sessionId=abc","scope=session&sessionId=9223372036854775808","scope=all&sessionId=-1"})
    void invalidParametersAre400(String query) throws Exception {
        report(subject,query).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void authenticationIsRequired() throws Exception {
        mvc.perform(get("/api/subjects/{id}/report?scope=all",subject)).andExpect(status().isUnauthorized());
    }
    @Test void readsNeverMutateDataCreateUsersOrCallAi() throws Exception {
        long s=session(USER,"2026-11-08T00:00:00Z","REVIEW"); answer(s,question,USER,true,true,"2026-11-08T00:01:00Z");
        var before=new HashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("app_user","subject","category","material","question","session","session_question","attempt")) before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        report(subject,"scope=all").andExpect(status().isOk()); report(subject,"scope=session&sessionId="+s).andExpect(status().isOk());
        sessionReport(s).andExpect(status().isOk());
        mvc.perform(get("/api/subjects/{id}/report?scope=all",subject).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString())))).andExpect(status().isNotFound());
        before.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(rows));
        verifyNoInteractions(ai);
    }
    @Test void swaggerDocumentsBothExamplesAndSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.responses['200'].content['application/json'].examples.session").exists())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.responses['200'].content['application/json'].examples.all").exists())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/report'].get.responses['404']").exists());
    }
    @Test void swaggerDocumentsSessionReportExampleStatusAndSecurity() throws Exception {
        String json=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/sessions/{id}/report'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/sessions/{id}/report'].get.responses['200'].content['application/json'].examples.session").exists())
                .andExpect(jsonPath("$.paths['/api/sessions/{id}/report'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/sessions/{id}/report'].get.responses['404']").exists())
                .andReturn().getResponse().getContentAsString();
        Map<String,Object> document=new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,Map.class);
        Map<String,Object> responseSchema=com.jayway.jsonpath.JsonPath.read(json,
                "$.paths['/api/sessions/{id}/report'].get.responses['200'].content['application/json'].schema");
        Map<String,Object> properties=castMap(resolveSchema(document,responseSchema).get("properties"));
        Map<String,Object> feedbackStatus=resolveSchema(document,castMap(properties.get("feedbackStatus")));
        assertThat((List<String>)feedbackStatus.get("enum")).containsExactlyInAnyOrder("PENDING","READY","SKIPPED","FAILED");
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> castMap(Object value) { return (Map<String,Object>)value; }
    private static Map<String,Object> resolveSchema(Map<String,Object> document,Map<String,Object> schema) {
        while(schema.containsKey("$ref")) {
            String ref=(String)schema.get("$ref");
            assertThat(ref).startsWith("#/components/schemas/");
            schema=castMap(castMap(castMap(document.get("components")).get("schemas"))
                    .get(ref.substring("#/components/schemas/".length())));
        }
        return schema;
    }
    ResultActions sessionReport(long id) throws Exception {
        return mvc.perform(get("/api/sessions/{id}/report",id).with(jwt().jwt(j->j.subject(USER.toString()))));
    }
    ResultActions report(long id,String query) throws Exception {
        return mvc.perform(get("/api/subjects/"+id+"/report?"+query).with(jwt().jwt(j->j.subject(USER.toString()))));
    }
    long subject(UUID user) { jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'과목')",user); return max("subject"); }
    long category(long s) {
        jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status) VALUES (?,'','PDF','EXPIRED')",s); long m=max("material");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',1,2,1,'AVAILABLE')",s,m); return max("category");
    }
    long question(long c) { jdbc.update("INSERT INTO question(category_id,type,stem,origin) VALUES (?,'SHORT','문제','PREGEN')",c); return max("question"); }
    long session(UUID user,String started,String mode) {
        jdbc.update("INSERT INTO session(user_id,mode,status,started_at,source) VALUES (?,?,'COMPLETED',?,'categories')",user,mode,time(started)); return max("session");
    }
    void member(long s,long q) { jdbc.update("INSERT INTO session_question VALUES (?,?,1)",s,q); }
    void answer(long s,long q,UUID user,Boolean correct,boolean self,String when) {
        member(s,q); jdbc.update("INSERT INTO attempt(user_id,session_id,question_id,answer,correct,self_judged,answered_at) VALUES (?,?,?,'답',?,?,?)",user,s,q,correct,self,time(when));
    }
    void repeat(long q,int count,int correct,String when) {
        for(int i=0;i<count;i++) answer(session(USER,null,i%2==0?"CURRICULUM":"REVIEW"),q,USER,i<correct,i%2==0,when);
    }
    OffsetDateTime time(String value) { return value==null?null:OffsetDateTime.parse(value); }
    long max(String table) { return jdbc.queryForObject("SELECT max(id) FROM "+table,Long.class); }
}
