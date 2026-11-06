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
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].put.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/users/me/nickname'].put.requestBody.content['application/json'].example.nickname").value("동현"));
    }

    @Test
    void swaggerUiRemainsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
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
