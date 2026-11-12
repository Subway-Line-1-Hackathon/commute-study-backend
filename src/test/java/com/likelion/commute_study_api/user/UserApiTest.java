package com.likelion.commute_study_api.user;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=",
        "SUPABASE_URL=https://auth.example.invalid",
        "spring.datasource.url=jdbc:h2:mem:user-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.data.jpa.repositories.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:user-test-schema.sql",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(UserApiTest.TokenConfig.class)
class UserApiTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final KeyPair KEYS = createKeys();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.likelion.commute_study_api.subject.SubjectRepository subjectRepository;

    @org.springframework.test.context.bean.override.convention.TestBean(name = "subjectClock", methodName = "fixedSubjectClock")
    java.time.Clock subjectClock;

    static java.time.Clock fixedSubjectClock() {
        // UTC is still November 6, but KST is already November 7.
        return java.time.Clock.fixed(Instant.parse("2026-11-06T15:30:00Z"), java.time.ZoneOffset.UTC);
    }

    @Test
    void creationUsesAuthenticatedUuidAndAutomaticallyCreatesUser() throws Exception {
        mvc.perform(post("/api/subjects").header("Authorization", token(USER, false))
                        .contentType("application/json").content("{\"name\":\"선형대수\",\"examDate\":\"2026-12-15\",\"userId\":\"" + OTHER + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subjectId").isNumber());
        assertThat(jdbc.queryForObject("SELECT user_id FROM public.subject", UUID.class)).isEqualTo(USER);
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id = ?", String.class, USER)).isNull();
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasSubject").value(true))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "가나다라마바사아자차카타파하가나다라마바", "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀"})
    void validNameBoundariesAndDuplicateNamesAreAllowed(String name) throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/subjects").header("Authorization", token(USER, false))
                            .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.subjectId").isNumber());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.subject WHERE user_id = ?", Integer.class, USER)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"   \"}",
            "{\"name\":\"123456789012345678901\"}", "{\"name\":123}",
            "{\"name\":\"수학\",\"examDate\":\"2026-02-30\"}",
            "{\"name\":\"수학\",\"examDate\":\"2026-02-29\"}",
            "{\"name\":\"수학\",\"examDate\":\"2026-2-01\"}",
            "{\"name\":\"수학\",\"examDate\":\"2026/12/15\"}",
            "{\"name\":\"수학\",\"examDate\":\"2026-12-15T00:00:00Z\"}",
            "{\"name\":\"수학\",\"examDate\":\"\"}",
            "{\"name\":\"수학\",\"examDate\":20261215}", "[]", "null", "{"})
    void invalidRequestsReturn400WithoutDatabaseWrites(String body) throws Exception {
        mvc.perform(post("/api/subjects").header("Authorization", token(USER, false))
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.subject", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
    }

    @Test
    void listIsEmptyForNewUserAndExcludesForeignSubjects() throws Exception {
        seed(OTHER, "다른사용자", true, true);
        mvc.perform(get("/api/subjects").header("Authorization", token(USER, false)))
                .andExpect(status().isOk()).andExpect(content().json("{\"subjects\":[]}"));
    }

    @Test
    void listContainsAllOwnSubjectsInIdOrderWithKstDaysAndOptionalFields() throws Exception {
        String bearer = token(USER, false);
        for (String date : new String[] {"2026-11-07", "2026-11-08", "2026-11-17", "2026-11-06", null}) {
            mvc.perform(post("/api/subjects").header("Authorization", bearer).contentType("application/json")
                            .content("{\"name\":\"수학\",\"examDate\":" + (date == null ? "null" : "\"" + date + "\"") + "}"))
                    .andExpect(status().isCreated());
        }
        seed(OTHER, "다른사용자", true, true);
        var ids = jdbc.queryForList("SELECT id FROM public.subject WHERE user_id = ? ORDER BY id", Long.class, USER);
        mvc.perform(get("/api/subjects").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjects.length()").value(5))
                .andExpect(jsonPath("$.subjects[0].id").value(ids.getFirst()))
                .andExpect(jsonPath("$.subjects[4].id").value(ids.getLast()))
                .andExpect(jsonPath("$.subjects[0].dDay").value(0))
                .andExpect(jsonPath("$.subjects[1].dDay").value(1))
                .andExpect(jsonPath("$.subjects[2].dDay").value(10))
                .andExpect(jsonPath("$.subjects[3].examDate").value("2026-11-06"))
                .andExpect(jsonPath("$.subjects[3].dDay").doesNotExist())
                .andExpect(jsonPath("$.subjects[4].examDate").doesNotExist())
                .andExpect(jsonPath("$.subjects[4].dDay").doesNotExist());
    }

    @Test
    void ownershipDoesNotRevealForeignOrMissingSubjects() {
        seed(USER, null, false, false);
        seed(OTHER, null, false, false);
        long id = subjectRepository.create(USER, "수학", null);
        subjectRepository.requireOwned(id, USER);
        assertThat(subjectRepository.isOwnedBy(id, USER)).isTrue();
        for (long target : new long[] {id, Long.MAX_VALUE}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> subjectRepository.requireOwned(target, OTHER))
                    .isInstanceOfSatisfying(com.likelion.commute_study_api.common.exception.ApiException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(
                                    com.likelion.commute_study_api.common.exception.CommonErrorCode.NOT_FOUND));
        }
    }

    @Test
    void subjectCreationCompletesSetupAndActivatesUser() throws Exception {
        String bearer = token(USER, false);
        mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                        .content("{\"nickname\":\"동현\",\"oneWayMin\":50}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.hasSubject").value(false));
        mvc.perform(post("/api/subjects").header("Authorization", bearer).contentType("application/json")
                        .content("{\"name\":\"프로그래밍\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.hasSubject").value(true));
    }

    @Test
    void failedSubjectInsertRollsBackAutomaticUserCreation() throws Exception {
        jdbc.execute("ALTER TABLE public.subject ADD CONSTRAINT subject_test_failure CHECK (name <> '실패')");
        try {
            mvc.perform(post("/api/subjects").header("Authorization", token(USER, false)).contentType("application/json")
                            .content("{\"name\":\"실패\"}"))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE public.subject DROP CONSTRAINT subject_test_failure");
        }
    }

    @Test
    void subjectApisRejectMissingOrExpiredJwt() throws Exception {
        for (String bearer : new String[] {"", token(USER, true)}) {
            mvc.perform(get("/api/subjects").header("Authorization", bearer)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/subjects").header("Authorization", bearer).contentType("application/json")
                            .content("{\"name\":\"수학\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }
    @Autowired com.likelion.commute_study_api.common.config.StudyProperties studyProperties;

    private static final String SETTINGS = "/api/users/me/settings";

    @ParameterizedTest
    @CsvSource({"10,REVIEW", "11,CURRICULUM", "50,CURRICULUM", "120,CURRICULUM"})
    void settingsSaveAndReadWithMode(int minutes, String mode) throws Exception {
        String bearer = token(USER, false);
        mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                        .content("{\"nickname\":\"동현\",\"oneWayMin\":" + minutes + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("동현"))
                .andExpect(jsonPath("$.studyMin").value(minutes)).andExpect(jsonPath("$.mode").value(mode));
        mvc.perform(get(SETTINGS).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.oneWayMin").value(minutes)).andExpect(jsonPath("$.studyMin").value(minutes))
                .andExpect(jsonPath("$.mode").value(mode))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"fromStation\":null")));
        mvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"));
        jdbc.update("INSERT INTO public.subject(user_id, name) VALUES (?, '자료구조')", USER);
        mvc.perform(get("/api/users/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"가나", "가나다라마바사아자차", "😀😀", "😀😀😀😀😀😀😀😀😀😀", "  동현  "})
    void settingsNicknamesUseTrimAndCodePoints(String nickname) throws Exception {
        mvc.perform(put(SETTINGS).header("Authorization", token(USER, false)).contentType("application/json")
                        .content("{\"nickname\":\"" + nickname + "\",\"oneWayMin\":50}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(nickname.trim()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"nickname\":\"가\",\"oneWayMin\":50}",
            "{\"nickname\":\"가나다라마바사아자차카\",\"oneWayMin\":50}",
            "{\"nickname\":\"   \",\"oneWayMin\":50}",
            "{\"nickname\":\"동현\",\"oneWayMin\":9}",
            "{\"nickname\":\"동현\",\"oneWayMin\":121}",
            "{\"oneWayMin\":50}", "{\"nickname\":null,\"oneWayMin\":50}",
            "{\"nickname\":\"동현\"}", "{\"nickname\":\"동현\",\"oneWayMin\":null}",
            "{\"nickname\":123,\"oneWayMin\":50}",
            "{\"nickname\":\"동현\",\"oneWayMin\":\"50\"}",
            "{\"nickname\":\"동현\",\"oneWayMin\":50.5}",
            "{\"nickname\":\"동현\",\"oneWayMin\":50.0}",
            "{\"nickname\":\"동현\",\"oneWayMin\":true}",
            "{\"nickname\":\"동현\",\"oneWayMin\":2147483648}",
            "{\"nickname\":\"동현\",\"oneWayMin\":50,\"fromStation\":123}",
            "{\"nickname\":[],\"oneWayMin\":50}", "[]", "null", "{", ""
    })
    void invalidSettingsReturnCommon400WithoutWrites(String body) throws Exception {
        mvc.perform(put(SETTINGS).header("Authorization", token(USER, false)).contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
    }

    @Test
    void settingsUpdatesAreIdempotentAndIsolatedAndAllowDuplicateNicknames() throws Exception {
        seed(OTHER, "동현", true, true);
        String bearer = token(USER, false);
        for (int minutes : new int[] {50, 19, 19}) {
            mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                            .content("{\"nickname\":\"동현\",\"oneWayMin\":" + minutes
                                    + ",\"fromStation\":\"출발\",\"toStation\":\"도착\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(SETTINGS).header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.oneWayMin").value(19)).andExpect(jsonPath("$.fromStation").value("출발"))
                .andExpect(jsonPath("$.toStation").value("도착"));
        mvc.perform(get(SETTINGS).header("Authorization", token(OTHER, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.oneWayMin").value(50))
                .andExpect(jsonPath("$.fromStation").value("수원역"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.commute_profile WHERE user_id = ?", Integer.class, USER))
                .isEqualTo(1);
        mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                        .content("{\"nickname\":\"수정\",\"oneWayMin\":20}"))
                .andExpect(status().isOk());
        mvc.perform(get(SETTINGS).header("Authorization", bearer)).andExpect(jsonPath("$.nickname").value("수정"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"toStation\":null")));
    }

    @Test
    void missingSettingsReturn404WithoutLeakingOtherSettings() throws Exception {
        seed(OTHER, "동현", true, true);
        mvc.perform(get(SETTINGS).header("Authorization", token(USER, false)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCommuteWriteRollsBackNicknameAndAutomaticUserCreation(boolean existing) throws Exception {
        if (existing) seed(USER, "이전", true, false);
        jdbc.execute("ALTER TABLE public.commute_profile ADD CONSTRAINT test_failure CHECK (one_way_min <> 19)");
        try {
            mvc.perform(put(SETTINGS).header("Authorization", token(USER, false)).contentType("application/json")
                            .content("{\"nickname\":\"변경\",\"oneWayMin\":19}"))
                    .andExpect(status().isInternalServerError());
            if (existing) {
                assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id = ?", String.class, USER)).isEqualTo("이전");
                assertThat(jdbc.queryForObject("SELECT one_way_min FROM public.commute_profile WHERE user_id = ?", Integer.class, USER)).isEqualTo(50);
            } else {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
            }
        } finally {
            jdbc.execute("ALTER TABLE public.commute_profile DROP CONSTRAINT test_failure");
        }
    }

    @Test
    void settingsRequireValidToken() throws Exception {
        for (String bearer : new String[] {"", token(USER, true)}) {
            mvc.perform(get(SETTINGS).header("Authorization", bearer)).andExpect(status().isUnauthorized());
            mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                            .content("{\"nickname\":\"동현\",\"oneWayMin\":50}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
    }

    @Test
    void settingsUseConfiguredStudyThreshold() throws Exception {
        int original = studyProperties.getCurriculumMinMinutes();
        try {
            studyProperties.setCurriculumMinMinutes(60);
            String bearer = token(USER, false);
            mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                            .content("{\"nickname\":\"동현\",\"oneWayMin\":50}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REVIEW"));
            mvc.perform(get(SETTINGS).header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REVIEW"));
        } finally {
            studyProperties.setCurriculumMinMinutes(original);
        }
    }

    @Test
    void concurrentSettingsSavesKeepOneCoherentProfile() throws Exception {
        String bearer = token(USER, false);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = java.util.stream.IntStream.range(0, 6).mapToObj(i -> pool.submit(() -> {
                start.await();
                return mvc.perform(put(SETTINGS).header("Authorization", bearer).contentType("application/json")
                                .content("{\"nickname\":\"사용자" + i + "\",\"oneWayMin\":" + (20 + i) + "}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            for (var result : results) assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.commute_profile", Integer.class)).isEqualTo(1);
        int minutes = jdbc.queryForObject("SELECT one_way_min FROM public.commute_profile WHERE user_id = ?", Integer.class, USER);
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id = ?", String.class, USER))
                .isEqualTo("사용자" + (minutes - 20));
    }

    @BeforeEach
    void clearIsolatedDatabase() {
        jdbc.update("DELETE FROM public.subject");
        jdbc.update("DELETE FROM public.commute_profile");
        jdbc.update("DELETE FROM public.app_user");
    }

    @Test
    void newUserIsCreatedWithoutGoogleNameAndSettingsAreOmitted() throws Exception {
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"nickname\":null")))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.hasSubject").value(false))
                .andExpect(jsonPath("$.settings").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id = ?", String.class, USER)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatedRequestsPreserveNicknameAndCreationTime() throws Exception {
        seed(USER, "동현", false, false);
        jdbc.update("UPDATE public.app_user SET created_at = '2020-01-01 00:00:00+00' WHERE id = ?", USER);
        var before = jdbc.queryForMap("SELECT * FROM public.app_user WHERE id = ?", USER);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("동현"));
        }
        assertThat(jdbc.queryForMap("SELECT * FROM public.app_user WHERE id = ?", USER)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentFirstRequestsCreateOneRow() throws Exception {
        String bearer = token(USER, false);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            Callable<Integer> request = () -> {
                start.await();
                return mvc.perform(get("/api/users/me").header("Authorization", bearer))
                        .andReturn().getResponse().getStatus();
            };
            var results = java.util.stream.IntStream.range(0, 6).mapToObj(i -> pool.submit(request)).toList();
            start.countDown();
            for (var result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
            "true,false,false", "true,false,true", "true,true,false", "true,true,true"})
    void statusRequiresAllThreeConditions(boolean nickname, boolean commute, boolean subject) throws Exception {
        seed(USER, nickname ? "동현" : null, commute, subject);
        var result = mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(nickname && commute && subject ? "ACTIVE" : "AUTHENTICATED"))
                .andExpect(jsonPath("$.hasSubject").value(subject));
        if (commute) {
            result.andExpect(jsonPath("$.settings.oneWayMin").value(50))
                    .andExpect(jsonPath("$.settings.studyMin").value(50))
                    .andExpect(jsonPath("$.settings.fromStation").value("수원역"))
                    .andExpect(jsonPath("$.settings.toStation").value("서울역"))
                    .andExpect(jsonPath("$.settings.mode").value("CURRICULUM"));
        } else {
            result.andExpect(jsonPath("$.settings").doesNotExist());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void unsetNicknameIsNotActive(String nickname) throws Exception {
        seed(USER, nickname, true, true);
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    @ParameterizedTest
    @CsvSource({"10,REVIEW", "11,CURRICULUM"})
    void reusesStudyModeBoundary(int minutes, String mode) throws Exception {
        seed(USER, "동현", true, true);
        jdbc.update("UPDATE public.commute_profile SET one_way_min = ?, from_station = NULL, to_station = NULL", minutes);
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.settings.studyMin").value(minutes))
                .andExpect(jsonPath("$.settings.mode").value(mode));
    }

    @Test
    void missingAndActuallyExpiredSignedTokensAreRejectedBeforeDatabaseAccess() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, true)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class)).isZero();
    }

    @Test
    void otherUsersDataDoesNotAffectResponse() throws Exception {
        seed(OTHER, "다른사용자", true, true);
        mvc.perform(get("/api/users/me").header("Authorization", token(USER, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasSubject").value(false))
                .andExpect(jsonPath("$.settings").doesNotExist())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"));
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id = ?", String.class, OTHER))
                .isEqualTo("다른사용자");
    }

    @Test
    void legacyReadsWorkBeforeAndAfterAutomaticCreation() throws Exception {
        String bearer = token(USER, false);
        mvc.perform(get("/api/auth/me").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nicknameSet").value(false))
                .andExpect(jsonPath("$.email").value("test@example.invalid"));
        mvc.perform(get("/api/users/me").header("Authorization", bearer)).andExpect(status().isOk());
        // H2 does not support the legacy PostgreSQL ON CONFLICT ... DO UPDATE.
        // Its HTTP update contract is covered by CorsConfigTest with a mocked JdbcTemplate.
        jdbc.update("UPDATE public.app_user SET nickname = ? WHERE id = ?", "동현", USER);
        for (String path : List.of("/api/auth/me", "/api/users/me/nickname")) {
            mvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nickname").value("동현"))
                    .andExpect(jsonPath("$.nicknameSet").value(true));
        }
    }

    private void seed(UUID id, String nickname, boolean commute, boolean subject) {
        jdbc.update("INSERT INTO public.app_user(id, nickname) VALUES (?, ?)", id, nickname);
        if (commute) {
            jdbc.update("INSERT INTO public.commute_profile VALUES (?, 50, '수원역', '서울역')", id);
        }
        if (subject) {
            jdbc.update("INSERT INTO public.subject(user_id, name) VALUES (?, '자료구조')", id);
        }
    }

    private static KeyPair createKeys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(UUID user, boolean expired) {
        var key = new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                .privateKey((RSAPrivateKey) KEYS.getPrivate()).keyID("test").build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(user.toString()).issuer("https://auth.example.invalid")
                .issuedAt(now.minusSeconds(600)).expiresAt(now.plusSeconds(expired ? -300 : 300))
                .claim("email", "test@example.invalid").claim("user_metadata", java.util.Map.of("full_name", "Google Name"))
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(), claims)).getTokenValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://auth.example.invalid"));
            return decoder;
        }
    }
}
