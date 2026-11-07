package com.likelion.commute_study_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.import=",
        "SUPABASE_URL=https://auth.example.invalid",
        "SUPABASE_PUBLISHABLE_KEY=sb_publishable_test",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class OpenApiConfigTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void documentsBearerAuthenticationAndNicknameExample() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/api/users/me'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/materials'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects/{id}/materials'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath("$.paths['/api/materials/{id}/status'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/materials/{id}/retry'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/subjects'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/subjects'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/subjects'].post.requestBody.content['application/json'].example.name").value("선형대수"))
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].get.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].put.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/api/users/me/settings'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/settings'].put.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/settings'].put.requestBody.content['application/json'].example.oneWayMin").value(50))
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].put.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].put.requestBody.content['application/json'].example.nickname").value("동현"));
    }

    @Test
    void swaggerUiRemainsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Google 로그인")));
        mvc.perform(get("/swagger-ui/auth-config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publishableKey").value("sb_publishable_test"));
        for (String asset : new String[] { "google-auth.js", "supabase.js",
                "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js", "swagger-ui.css" }) {
            mvc.perform(get("/swagger-ui/" + asset)).andExpect(status().isOk());
        }
    }

    @Test
    void bearerHeaderStillAuthenticatesMeWithoutChangingApiContract() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("manual-test-token")
                .header("alg", "ES256").subject("11111111-1111-1111-1111-111111111111")
                .claim("email", "test@example.invalid").build();
        org.mockito.Mockito.when(jwtDecoder.decode("manual-test-token")).thenReturn(jwt);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer manual-test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(jwt.getSubject()))
                .andExpect(jsonPath("$.email").value("test@example.invalid"));
    }

    @Test
    void documentedApisStillRequireAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/nickname")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/users/me/nickname")
                        .contentType("application/json").content("{\"nickname\":\"동현\"}"))
                .andExpect(status().isUnauthorized());
    }
}
