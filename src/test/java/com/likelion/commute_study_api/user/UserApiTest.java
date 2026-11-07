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
    @CsvSource({"19,REVIEW", "20,CURRICULUM"})
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
