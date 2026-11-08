package com.likelion.commute_study_api.user;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=",
        "SUPABASE_URL=https://auth.example.invalid",
        "spring.datasource.url=jdbc:h2:mem:legacy-auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:user-test-schema.sql",
        "material.cleanup-enabled=false", "question.pregen-retry-enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
})
@AutoConfigureMockMvc
class LegacyAuthApiTest {
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String NICKNAME = "/api/users/me/nickname";
    private static final String SETTINGS = "/api/users/me/settings";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM public.subject");
        jdbc.update("DELETE FROM public.commute_profile");
        jdbc.update("DELETE FROM public.app_user");
        when(decoder.decode("valid-token")).thenReturn(jwt(USER, true));
        when(decoder.decode("no-email")).thenReturn(jwt(USER, false));
    }

    private Jwt jwt(UUID userId, boolean email) {
        var builder = Jwt.withTokenValue("test-token").header("alg", "RS256")
                .subject(userId.toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        if (email) builder.claim("email", "test@example.invalid");
        return builder.build();
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer valid-token");
    }

    @Test
    void legacyReadsPreserveNullFieldsAndDoNotCreateUser() throws Exception {
        var auth = mvc.perform(authenticated(get("/api/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.email").value("test@example.invalid"))
                .andExpect(jsonPath("$.nicknameSet").value(false)).andReturn();
        var body = JSON.readTree(auth.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(4);
        assertThat(body.has("nickname")).isTrue();
        assertThat(body.get("nickname").isNull()).isTrue();
        var nickname = mvc.perform(authenticated(get(NICKNAME))).andExpect(status().isOk()).andReturn();
        var nickBody = JSON.readTree(nickname.getResponse().getContentAsString());
        assertThat(nickBody.size()).isEqualTo(3);
        assertThat(nickBody.get("nickname").isNull()).isTrue();
        var noEmail = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer no-email"))
                .andExpect(status().isOk()).andReturn();
        assertThat(JSON.readTree(noEmail.getResponse().getContentAsString()).get("email").isNull()).isTrue();
        assertThat(userCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"  동현  ", "ab", "abcdefghij", "😀😀", "😀😀😀😀😀😀😀😀😀😀"})
    void nicknameUpsertPreservesSuccessContractWithoutCommuteProfile(String nickname) throws Exception {
        for (int i = 0; i < 2; i++) {
            var result = mvc.perform(authenticated(put(NICKNAME)).contentType("application/json")
                            .content(JSON.writeValueAsString(java.util.Map.of("nickname", nickname, "userId", OTHER.toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(USER.toString()))
                    .andExpect(jsonPath("$.nickname").value(nickname.trim()))
                    .andExpect(jsonPath("$.message").value("닉네임 저장 성공")).andReturn();
            assertThat(JSON.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(3);
        }
        assertThat(userCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.commute_profile", Integer.class)).isZero();
        mvc.perform(authenticated(get("/api/auth/me"))).andExpect(jsonPath("$.nicknameSet").value(true))
                .andExpect(jsonPath("$.nickname").value(nickname.trim()));
        mvc.perform(authenticated(get(NICKNAME))).andExpect(jsonPath("$.nicknameSet").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nickname\":null}", "{\"nickname\":\"\"}",
            "{\"nickname\":\"   \"}", "{\"nickname\":\"가\"}", "{\"nickname\":\"😀\"}",
            "{\"nickname\":\"abcdefghijk\"}", "{\"nickname\":\"😀😀😀😀😀😀😀😀😀😀😀\"}",
            "{\"nickname\":123}", "{\"nickname\":true}", "{\"nickname\":[]}", "{\"nickname\":{}}",
            "[]", "null", "123", "\"동현\"", "{"})
    void invalidNicknameAndJsonReturnShared400WithoutWrites(String body) throws Exception {
        var response = mvc.perform(authenticated(put(NICKNAME)).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn();
        var json = JSON.readTree(response.getResponse().getContentAsString());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("details")).isTrue();
        assertThat(userCount()).isZero();
    }

    @Test
    void nicknameOnlyUpdatePreservesSettingsCreatedAtAndOtherUsers() throws Exception {
        mvc.perform(authenticated(put(SETTINGS)).contentType("application/json")
                        .content("{\"nickname\":\"기존닉네임\",\"oneWayMin\":50,\"fromStation\":\"수원\",\"toStation\":\"서울\"}"))
                .andExpect(status().isOk());
        jdbc.update("INSERT INTO public.app_user(id,nickname) VALUES (?,?)", OTHER, "다른사용자");
        var created = jdbc.queryForObject("SELECT created_at FROM public.app_user WHERE id=?", java.time.OffsetDateTime.class, USER);
        mvc.perform(authenticated(put(NICKNAME)).contentType("application/json").content("{\"nickname\":\"변경닉네임\"}"))
                .andExpect(status().isOk());
        mvc.perform(authenticated(get(SETTINGS))).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("변경닉네임")).andExpect(jsonPath("$.oneWayMin").value(50))
                .andExpect(jsonPath("$.fromStation").value("수원")).andExpect(jsonPath("$.toStation").value("서울"));
        mvc.perform(authenticated(get("/api/users/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("변경닉네임"))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED")).andExpect(jsonPath("$.hasSubject").value(false));
        assertThat(jdbc.queryForObject("SELECT created_at FROM public.app_user WHERE id=?", java.time.OffsetDateTime.class, USER)).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id=?", String.class, OTHER)).isEqualTo("다른사용자");
        mvc.perform(authenticated(put(NICKNAME)).contentType("application/json").content("{\"nickname\":false}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id=?", String.class, USER)).isEqualTo("변경닉네임");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/users/me/nickname", "/api/users/me", "/api/users/me/settings"})
    void missingBearerReturnsShared401(String path) throws Exception {
        assertUnauthorized(get(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me/nickname", "/api/users/me/settings"})
    void missingBearerOnPutReturns401BeforeValidation(String path) throws Exception {
        assertUnauthorized(put(path).contentType("application/json").content("{"));
        assertThat(userCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer bad token", "Bearer", "Basic abc", "Bearer malformed", "Bearer expired", "Bearer signature"})
    void bearerFailuresAreJsonAndDoNotLeakDecoderDetails(String header) throws Exception {
        when(decoder.decode("malformed")).thenThrow(new BadJwtException("PRIVATE malformed token"));
        when(decoder.decode("expired")).thenThrow(new JwtValidationException("PRIVATE expired token",
                List.of(new OAuth2Error("invalid_token", "PRIVATE expiry detail", null))));
        when(decoder.decode("signature")).thenThrow(new BadJwtException("PRIVATE signature/key detail"));
        assertUnauthorized(get("/api/auth/me").header("Authorization", header).header("Accept", "text/html"));
        assertThat(userCount()).isZero();
    }

    private void assertUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        var result = mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andReturn();
        var json = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("code").stringValue()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("message").stringValue()).isEqualTo("인증에 실패했습니다.");
        assertThat(json.get("details").isNull()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("PRIVATE", "token", "signature");
    }

    @Test
    void openApiRetainsSixRoutesDeprecationAndErrorExamples() throws Exception {
        var result = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var paths = JSON.readTree(result.getResponse().getContentAsString()).get("paths");
        for (String path : List.of("/api/auth/me", NICKNAME, "/api/users/me", SETTINGS)) {
            var operation = paths.get(path).get("get");
            assertThat(operation.get("responses").has("200")).as(path).isTrue();
            assertThat(operation.get("responses").has("401")).as(path).isTrue();
            assertThat(operation.path("deprecated").asBoolean()).isEqualTo(path.equals("/api/auth/me") || path.equals(NICKNAME));
        }
        for (String path : List.of(NICKNAME, SETTINGS)) {
            var operation = paths.get(path).get("put");
            assertThat(operation.get("responses").has("200")).as(path).isTrue();
            assertThat(operation.get("responses").has("400")).as(path).isTrue();
            assertThat(operation.get("responses").has("401")).as(path).isTrue();
            assertThat(operation.path("deprecated").asBoolean()).isEqualTo(path.equals(NICKNAME));
            var media = operation.get("responses").get("400").get("content").get("application/json");
            assertThat(media.has("examples") || media.has("example")).as(path + " 400 example").isTrue();
        }
    }

    @Test
    void concurrentFirstSavesCreateOneUser() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                String nickname = "닉네임" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    mvc.perform(authenticated(put(NICKNAME)).contentType("application/json")
                                    .content(JSON.writeValueAsString(java.util.Map.of("nickname", nickname))))
                            .andExpect(status().isOk());
                    return null;
                }));
            }
            start.countDown();
            for (var result : results) result.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(userCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT nickname FROM public.app_user WHERE id=?", String.class, USER))
                .isIn("닉네임0", "닉네임1", "닉네임2", "닉네임3");
    }

    @Test
    void invalidSubjectUsesSharedUnauthorizedWithoutDatabaseWrites() throws Exception {
        when(decoder.decode("invalid-subject")).thenReturn(Jwt.withTokenValue("invalid-subject")
                .header("alg", "RS256").subject("not-a-uuid").build());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer invalid-subject"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증에 실패했습니다."));
        assertThat(userCount()).isZero();
    }

    private int userCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.app_user", Integer.class);
    }
}
