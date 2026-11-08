package com.likelion.commute_study_api.wrongnote;

import java.time.*;
import java.util.*;
import com.likelion.commute_study_api.common.config.WrongNoteProperties;
import com.likelion.commute_study_api.session.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.config.import=","SUPABASE_URL=https://auth.example.invalid","material.cleanup-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:wrong-notes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false","spring.data.jpa.repositories.enabled=false","spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:study-read-test-schema.sql,classpath:wrong-note-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"})
@AutoConfigureMockMvc
class WrongNoteApiTest {
    static final UUID USER=UUID.fromString("11111111-1111-1111-1111-111111111111"),OTHER=UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final OffsetDateTime TIME=OffsetDateTime.parse("2026-11-08T13:30:00Z");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WrongNoteProperties properties;
    @Autowired WrongNoteRepository repository;
    @Autowired SessionRepository sessions;
    @Autowired AttemptRepository attempts;
    @MockitoBean JwtDecoder decoder;
    long subject,secondSubject,foreignSubject,category,secondCategory,foreignCategory;
    @BeforeEach void seed() {
        properties.setMaxOpen(100); properties.setMaxResolved(50);
        for(String table:List.of("wrong_note","attempt","session_question","session","question","category","material_page","material","subject","app_user")) jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO app_user(id) VALUES (?),(?)",USER,OTHER);
        subject=subject(USER); secondSubject=subject(USER); foreignSubject=subject(OTHER);
        category=category(subject); secondCategory=category(subject); foreignCategory=category(foreignSubject);
    }
    @Test void fieldsStatusesCountsTimestampAndMixedOrderingAreExact() throws Exception {
        long old=note(category,USER,"OPEN",3,TIME.minusHours(1));
        long resolved=note(category,USER,"RESOLVED",2,TIME);
        long tie=note(secondCategory,USER,"OPEN",7,TIME);
        list(subject,null).andExpect(status().isOk()).andExpect(jsonPath("$.items[*].questionId",contains((int)tie,(int)resolved,(int)old)))
                .andExpect(jsonPath("$.items[0]",aMapWithSize(6))).andExpect(jsonPath("$.items[0].categoryId").value(secondCategory))
                .andExpect(jsonPath("$.items[0].stem").value("문제" )).andExpect(jsonPath("$.items[0].wrongCount").value(7))
                .andExpect(jsonPath("$.items[1].status").value("RESOLVED")).andExpect(jsonPath("$.items[2].status").value("OPEN"));
        var items=repository.list(subject,USER,null,100,50);
        assertThat(items.getFirst().lastWrongAt().toInstant()).isEqualTo(TIME.toInstant());
        assertThat(items.get(1).wrongCount()).isEqualTo(2);
    }
    @Test void noNotesAndEmptyCategoryReturnEmptyItems() throws Exception {
        list(subject,null).andExpect(status().isOk()).andExpect(content().json("{\"items\":[]}"));
        note(category,USER,"OPEN",1,TIME);
        list(subject,secondCategory).andExpect(content().json("{\"items\":[]}"));
        list(secondSubject,null).andExpect(content().json("{\"items\":[]}"));
    }
    @Test void defaultCapsKeepNewestHundredAndFiftyWithoutDeletingAnyRows() throws Exception {
        var open=new ArrayList<Long>(); var resolved=new ArrayList<Long>();
        for(int i=0;i<101;i++) open.add(note(category,USER,"OPEN",1,TIME.plusSeconds(i)));
        for(int i=0;i<51;i++) resolved.add(note(category,USER,"RESOLVED",1,TIME.plusSeconds(i)));
        list(subject,null).andExpect(jsonPath("$.items",hasSize(150)))
                .andExpect(jsonPath("$.items[?(@.status == 'OPEN')]",hasSize(100)))
                .andExpect(jsonPath("$.items[?(@.status == 'RESOLVED')]",hasSize(50)));
        var items=repository.list(subject,USER,null,properties.getMaxOpen(),properties.getMaxResolved());
        assertThat(items).extracting(WrongNoteRepository.Item::questionId).doesNotContain(open.getFirst(),resolved.getFirst())
                .contains(open.getLast(),resolved.getLast());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrong_note",Integer.class)).isEqualTo(152);
    }
    @Test void configurationChangesAreAppliedIndependently() throws Exception {
        for(int i=0;i<4;i++) { note(category,USER,"OPEN",1,TIME); note(category,USER,"RESOLVED",1,TIME); }
        properties.setMaxOpen(2); properties.setMaxResolved(1);
        list(subject,null).andExpect(jsonPath("$.items",hasSize(3)))
                .andExpect(jsonPath("$.items[?(@.status == 'OPEN')]",hasSize(2)))
                .andExpect(jsonPath("$.items[?(@.status == 'RESOLVED')]",hasSize(1)));
        properties.setMaxOpen(0); properties.setMaxResolved(0);
        list(subject,null).andExpect(jsonPath("$.items",hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrong_note",Integer.class)).isEqualTo(8);
    }
    @Test void categoryFilterRunsAfterSubjectCapsAndMatchesReplayCandidatesIncludingTies() throws Exception {
        properties.setMaxOpen(2); properties.setMaxResolved(1);
        long excluded=note(category,USER,"OPEN",1,TIME);
        long included=note(category,USER,"OPEN",1,TIME);
        long otherCategory=note(secondCategory,USER,"OPEN",1,TIME);
        note(category,USER,"RESOLVED",1,TIME.minusSeconds(1));
        long resolvedOther=note(secondCategory,USER,"RESOLVED",1,TIME);
        list(subject,category).andExpect(jsonPath("$.items",hasSize(1))).andExpect(jsonPath("$.items[0].questionId").value(included));
        list(subject,secondCategory).andExpect(jsonPath("$.items[*].questionId",contains((int)resolvedOther,(int)otherCategory)));
        var candidates=sessions.findCandidates(USER,List.of(category),List.of("OX"),2,properties.getMaxOpen());
        assertThat(candidates.stream().filter(SessionRepository.CandidateQuestion::wrongListed).map(SessionRepository.CandidateQuestion::id).toList())
                .containsExactly(included).doesNotContain(excluded);
        var all=sessions.findCandidates(USER,List.of(),List.of("OX"),2,properties.getMaxOpen());
        assertThat(all.stream().filter(SessionRepository.CandidateQuestion::wrongListed).map(SessionRepository.CandidateQuestion::id).toList())
                .containsExactlyInAnyOrder(included,otherCategory);
    }
    @Test void limitsArePerSubjectAndOtherUsersNotesDoNotAffectRanking() throws Exception {
        properties.setMaxOpen(1);
        long own=note(category,USER,"OPEN",1,TIME.minusDays(1));
        note(category,OTHER,"OPEN",99,TIME); // Invalid foreign ownership in fixture must still be excluded.
        note(foreignCategory,USER,"OPEN",99,TIME);
        note(foreignCategory,OTHER,"OPEN",99,TIME);
        long otherSubjectNote=note(category(secondSubject),USER,"OPEN",1,TIME);
        list(subject,null).andExpect(jsonPath("$.items",hasSize(1))).andExpect(jsonPath("$.items[0].questionId").value(own));
        list(secondSubject,null).andExpect(jsonPath("$.items[0].questionId").value(otherSubjectNote));
    }
    @Test void missingForeignAndUnrelatedCategoryHaveSame404Contract() throws Exception {
        long unrelated=category(secondSubject);
        String expected=null;
        for(long c:List.of(999999L,foreignCategory,unrelated)) {
            String response=list(subject,c).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            if(expected==null) expected=response; else assertThat(response).isEqualTo(expected);
        }
        for(long s:List.of(999999L,foreignSubject)) list(s,null).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"","0","-1","abc","1.5","9223372036854775808"})
    void invalidCategoryIs400(String value) throws Exception {
        mvc.perform(get("/api/subjects/{id}/wrong-notes",subject).param("categoryId",value).with(jwt().jwt(j->j.subject(USER.toString()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void jwtIsRequiredAndMalformedIdentityIsRejected() throws Exception {
        mvc.perform(get("/api/subjects/{id}/wrong-notes",subject)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/subjects/{id}/wrong-notes",subject).with(jwt().jwt(j->j.subject("bad")))).andExpect(status().isUnauthorized());
    }
    @Test void existingResolverIsImmediatelyReflectedWithoutDeletingNoteOrChangingCounts() throws Exception {
        long q=note(category,USER,"OPEN",3,TIME);
        assertThat(attempts.resolveWrong(USER,q)).isTrue();
        assertThat(attempts.resolveWrong(USER,q)).isFalse();
        list(subject,null).andExpect(jsonPath("$.items[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$.items[0].wrongCount").value(3));
        assertThat(sessions.findCandidates(USER,List.of(category),List.of("OX"),2,100).getFirst().wrongListed()).isFalse();
        // A later persisted wrong answer is read afresh, with no read-side state cache or transition.
        jdbc.update("UPDATE wrong_note SET wrong_count=wrong_count+1,status='OPEN',last_wrong_at=? WHERE user_id=? AND question_id=?",TIME.plusHours(1),USER,q);
        list(subject,null).andExpect(jsonPath("$.items[0].status").value("OPEN")).andExpect(jsonPath("$.items[0].wrongCount").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wrong_note",Integer.class)).isEqualTo(1);
    }
    @Test void readingDoesNotWriteOrCreateUserAndExpiredMaterialStillWorks() throws Exception {
        note(category,USER,"OPEN",1,TIME); note(category,USER,"RESOLVED",2,TIME);
        var before=new HashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("app_user","subject","category","material","question","wrong_note","attempt","session")) before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        list(subject,null).andExpect(status().isOk()); list(subject,category).andExpect(status().isOk());
        mvc.perform(get("/api/subjects/{id}/wrong-notes",subject).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString())))).andExpect(status().isNotFound());
        before.forEach((table,rows)->assertThat(jdbc.queryForList("SELECT * FROM "+table)).isEqualTo(rows));
    }
    @Test void swaggerHasBearerExamplesAndErrors() throws Exception {
        var result=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/wrong-notes'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/wrong-notes'].get.responses['200'].content['application/json'].examples.empty").exists());
        for(String code:List.of("400","401","404")) result.andExpect(jsonPath("$.paths['/api/subjects/{id}/wrong-notes'].get.responses['"+code+"']").exists());
    }
    ResultActions list(long id,Long filter) throws Exception {
        var request=get("/api/subjects/{id}/wrong-notes",id).with(jwt().jwt(j->j.subject(USER.toString())));
        if(filter!=null) request.param("categoryId",filter.toString());
        return mvc.perform(request);
    }
    long subject(UUID user) { jdbc.update("INSERT INTO subject(user_id,name) VALUES (?,'과목')",user); return max("subject"); }
    long category(long s) {
        jdbc.update("INSERT INTO material(subject_id,file_url,file_type,status) VALUES (?,'','PDF','EXPIRED')",s); long m=max("material");
        jdbc.update("INSERT INTO category(subject_id,material_id,name,page_start,page_end,\"order\",question_status) VALUES (?,?,'단원',1,2,1,'AVAILABLE')",s,m); return max("category");
    }
    long note(long c,UUID user,String status,int count,OffsetDateTime at) {
        jdbc.update("INSERT INTO question(category_id,type,difficulty,stem,answer,origin) VALUES (?,'OX',2,'문제','O','PREGEN')",c); long q=max("question");
        jdbc.update("INSERT INTO wrong_note VALUES (?,?,?,?,?)",user,q,count,status,at); return q;
    }
    long max(String table) { return jdbc.queryForObject("SELECT max(id) FROM "+table,Long.class); }
}
