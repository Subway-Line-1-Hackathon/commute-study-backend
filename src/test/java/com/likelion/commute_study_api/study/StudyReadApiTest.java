package com.likelion.commute_study_api.study;

import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","SUPABASE_URL=https://auth.example.invalid",
        "material.cleanup-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:study-read;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false","spring.data.jpa.repositories.enabled=false","spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:study-read-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class StudyReadApiTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;
    long subject,foreignSubject,material,foreignMaterial;

    @BeforeEach void setup() {
        for(String table:List.of("attempt","session_question","session","question","category","material_page","material","subject","app_user"))
            jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?),(?)",USER,OTHER);
        subject=subject(USER); foreignSubject=subject(OTHER);
        material=material(subject); foreignMaterial=material(foreignSubject);
    }

    @Test void missingAndForeignSubjectsAre404AndEmptyOwnedSubjectHasNoRecommendation() throws Exception {
        mvc.perform(get("/api/subjects/{id}/categories",foreignSubject).with(jwt().jwt(j->j.subject(USER.toString())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/subjects/999999/categories").with(jwt().jwt(j->j.subject(USER.toString())))).andExpect(status().isNotFound());
        categories().andExpect(status().isOk()).andExpect(jsonPath("$.categories",hasSize(0)))
                .andExpect(jsonPath("$.recommendedId").value(nullValue()));
    }

    @Test void preparingOverridesProgressAndIsNeverRecommended() throws Exception {
        long c=category("PREPARING",1); attempt(question(c,"PREGEN"),USER,true,false,"2026-11-08T01:00:00Z");
        categories().andExpect(jsonPath("$.categories[0].state").value("PREPARING"))
                .andExpect(jsonPath("$.categories[0].questionStatus").value("PREPARING"))
                .andExpect(jsonPath("$.categories[0].progress").value(1.0))
                .andExpect(jsonPath("$.recommendedId").value(nullValue()));
    }

    @Test void categoryItemIncludesMaterialIdAndSummary() throws Exception {
        long c=category("AVAILABLE",1);
        jdbc.update("UPDATE category SET summary='요약' WHERE id=?",c);
        categories().andExpect(jsonPath("$.categories[0].materialId").value(material))
                .andExpect(jsonPath("$.categories[0].summary").value("요약"));
    }

    @Test void categorySummaryIsNullWhenNotSet() throws Exception {
        category("AVAILABLE",1);
        categories().andExpect(jsonPath("$.categories[0].summary").value(nullValue()));
    }

    @Test void unstartedUsesCategoryOrderThenIdAndNoAttemptsMeansNullAccuracyAndTime() throws Exception {
        long late=category("AVAILABLE",5), first=category("AVAILABLE",1), tie=category("AVAILABLE",1);
        for(long c:List.of(late,first,tie)) question(c,"PREGEN");
        categories().andExpect(jsonPath("$.categories[*].id",contains((int)first,(int)tie,(int)late)))
                .andExpect(jsonPath("$.categories[0].state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.categories[0].progress").value(0.0))
                .andExpect(jsonPath("$.categories[0].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.categories[0].lastStudiedAt").value(nullValue()))
                .andExpect(jsonPath("$.recommendedId").value(first));
    }

    @Test void distinctPregenProgressAndAllGradedAttemptAccuracyAreIndependent() throws Exception {
        long c=category("AVAILABLE",1), q1=question(c,"PREGEN"), q2=question(c,"PREGEN"), extra=question(c,"EXTRA");
        attempt(q1,USER,true,false,"2026-11-08T01:00:00Z");
        attempt(q1,USER,false,false,"2026-11-08T02:00:00Z");
        jdbc.update("UPDATE question SET type='SHORT' WHERE id=?",extra);
        attempt(extra,USER,true,true,"2026-11-08T03:00:00Z");
        attempt(q2,USER,null,false,"2026-11-08T04:00:00Z");
        // Even corrupt cross-user attempt data must never enter the owner's statistics.
        attempt(q2,OTHER,true,false,"2026-11-09T04:00:00Z");
        categories().andExpect(jsonPath("$.categories[0].progress").value(0.5))
                .andExpect(jsonPath("$.categories[0].accuracy",closeTo(2.0/3,0.000001)))
                .andExpect(jsonPath("$.categories[0].state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.recommendedId").value(c));
        var json=new tools.jackson.databind.ObjectMapper().readTree(categories().andReturn().getResponse().getContentAsString());
        assertThat(OffsetDateTime.parse(json.at("/categories/0/lastStudiedAt").stringValue()).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-08T04:00:00Z").toInstant());
    }

    @Test void completedCountsWrongAnswersAsSolvedAndDoesNotRecommendCompletedCategories() throws Exception {
        long c=category("AVAILABLE",1);
        attempt(question(c,"PREGEN"),USER,false,false,"2026-11-08T01:00:00Z");
        attempt(question(c,"PREGEN"),USER,true,true,"2026-11-08T02:00:00Z");
        for(int i=0;i<4;i++) question(c,"EXTRA");
        categories().andExpect(jsonPath("$.categories[0].progress").value(1.0))
                .andExpect(jsonPath("$.categories[0].accuracy").value(0.5))
                .andExpect(jsonPath("$.categories[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.recommendedId").value(nullValue()));
    }

    @Test void zeroPregenIsSafeEvenWithExtraAnswers() throws Exception {
        long empty=category("AVAILABLE",1), extraOnly=category("AVAILABLE",2);
        attempt(question(extraOnly,"EXTRA"),USER,true,true,"2026-11-08T01:00:00Z");
        categories().andExpect(jsonPath("$.categories[*].progress",contains(0.0,0.0)))
                .andExpect(jsonPath("$.categories[*].state",contains("NOT_STARTED","NOT_STARTED")))
                .andExpect(jsonPath("$.categories[0].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.categories[1].accuracy").value(1.0))
                .andExpect(jsonPath("$.recommendedId").value(empty));
    }

    @Test void pendingOnlyDoesNotCountAsCompletedOrGraded() throws Exception {
        long c=category("AVAILABLE",1);
        attempt(question(c,"PREGEN"),USER,null,false,"2026-11-08T01:00:00Z");
        categories().andExpect(jsonPath("$.categories[0].progress").value(0.0))
                .andExpect(jsonPath("$.categories[0].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.categories[0].state").value("NOT_STARTED"))
                .andExpect(jsonPath("$.categories[0].lastStudiedAt").isString());
    }

    @Test void recommendsMostRecentlyStudiedInProgressBeforeUnstartedAndExcludesPreparing() throws Exception {
        category("AVAILABLE",0);
        long old=category("AVAILABLE",1), recent=category("AVAILABLE",2), preparing=category("PREPARING",3);
        for(long c:List.of(old,recent,preparing)) {
            question(c,"PREGEN");
            attempt(question(c,"PREGEN"),USER,true,false,c==old?"2026-11-08T01:00:00Z":c==recent?"2026-11-08T02:00:00Z":"2026-11-08T03:00:00Z");
        }
        categories().andExpect(jsonPath("$.recommendedId").value(recent));
    }

    @Test void otherUsersAttemptsCannotCreateProgressAccuracyOrLastStudied() throws Exception {
        long c=category("AVAILABLE",1);
        attempt(question(c,"PREGEN"),OTHER,true,false,"2026-11-08T01:00:00Z");
        categories().andExpect(jsonPath("$.categories[0].progress").value(0.0))
                .andExpect(jsonPath("$.categories[0].accuracy").value(nullValue()))
                .andExpect(jsonPath("$.categories[0].lastStudiedAt").value(nullValue()));
    }

    @ParameterizedTest @ValueSource(strings={"READY","EXPIRED"})
    void explanationRemainsAvailableAndReturnsOnlyTwoFields(String state) throws Exception {
        long c=category("AVAILABLE",1), q=question(c,"PREGEN");
        jdbc.update("UPDATE material SET status=? WHERE id=?",state,material);
        jdbc.update("UPDATE question SET explanation='해설',source_page=7,answer='비공개 정답' WHERE id=?",q);
        mvc.perform(get("/api/questions/{id}/explanation",q).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(content().json("{\"explanation\":\"해설\",\"sourcePage\":7}"))
                .andExpect(jsonPath("$",aMapWithSize(2)));
        categories().andExpect(status().isOk()).andExpect(jsonPath("$.categories",hasSize(1)));
    }

    @Test void nullExplanationAndPageAreExplicitNulls() throws Exception {
        long q=question(category("AVAILABLE",1),"PREGEN");
        mvc.perform(get("/api/questions/{id}/explanation",q).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$",aMapWithSize(2)))
                .andExpect(jsonPath("$.explanation").value(nullValue())).andExpect(jsonPath("$.sourcePage").value(nullValue()));
    }

    @Test void missingAndForeignQuestionsAre404() throws Exception {
        long c=category("AVAILABLE",1), q=question(c,"PREGEN");
        jdbc.update("UPDATE category SET subject_id=?,material_id=? WHERE id=?",foreignSubject,foreignMaterial,c);
        for(long id:List.of(q,999999L)) mvc.perform(get("/api/questions/{id}/explanation",id).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest @ValueSource(strings={"/api/subjects/1/categories","/api/questions/1/explanation"})
    void authenticationIsRequiredAndInvalidSubjectIsRejected(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().jwt(j->j.subject("invalid-uuid")))).andExpect(status().isUnauthorized());
    }

    @Test void readsDoNotCreateUsersOrMutateAnyStudyRows() throws Exception {
        long q=question(category("AVAILABLE",1),"PREGEN");
        attempt(q,USER,true,true,"2026-11-08T01:00:00Z");
        var before=new HashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("app_user","subject","material","category","question","session","session_question","attempt"))
            before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        categories().andExpect(status().isOk());
        mvc.perform(get("/api/questions/{id}/explanation",q).with(jwt().jwt(j->j.subject(USER.toString())))).andExpect(status().isOk());
        mvc.perform(get("/api/subjects/{id}/categories",subject).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString())))).andExpect(status().isNotFound());
        before.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(rows));
    }

    @Test void swaggerContainsBothSecuredOperationsAndExamples() throws Exception {
        for(String path:List.of("/api/subjects/{id}/categories","/api/questions/{id}/explanation")) {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['"+path+"'].get.security[0].bearerAuth").isArray())
                    .andExpect(jsonPath("$.paths['"+path+"'].get.responses['200'].content['application/json']").exists())
                    .andExpect(jsonPath("$.paths['"+path+"'].get.responses['404']").exists());
        }
    }

    org.springframework.test.web.servlet.ResultActions categories() throws Exception {
        return mvc.perform(get("/api/subjects/{id}/categories",subject).with(jwt().jwt(j->j.subject(USER.toString()))));
    }
    long subject(UUID user) { jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'과목')",user); return max("subject"); }
    long material(long s) { jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status) VALUES (?,'private/ref.pdf','PDF','READY')",s); return max("material"); }
    long category(String status,int order) {
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',1,12,?,?)",subject,material,order,status);
        return max("category");
    }
    long question(long c,String origin) { jdbc.update("INSERT INTO question(category_id,type,stem,origin) VALUES (?,'OX','문제',?)",c,origin); return max("question"); }
    void attempt(long q,UUID user,Boolean correct,boolean self,String when) {
        jdbc.update("INSERT INTO session(user_id,mode) VALUES (?,'CURRICULUM')",user);
        long s=max("session"); jdbc.update("INSERT INTO session_question VALUES (?,?,1)",s,q);
        jdbc.update("INSERT INTO attempt(user_id,question_id,session_id,answer,correct,self_judged,answered_at) VALUES (?,?,?,'답안',?,?,?)",
                user,q,s,correct,self,OffsetDateTime.parse(when));
    }
    long max(String table) { return jdbc.queryForObject("SELECT max(id) FROM "+table,Long.class); }
}
