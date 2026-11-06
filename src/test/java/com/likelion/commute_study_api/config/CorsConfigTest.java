package com.likelion.commute_study_api.config;

import com.likelion.commute_study_api.controller.NicknameController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NicknameController.class, properties = {
        "spring.config.import=",
        "SUPABASE_URL=https://auth.example.com"
})
@Import({CorsConfig.class, SecurityConfig.class})
class CorsConfigTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private static final String ORIGIN = "http://localhost:5500";
    private static final String PATH = "/api/users/me/nickname";
    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void preflightAllowsMethodsAndUploadAndSseHeadersWithoutAuthentication(String method) throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers",
                                "authorization,content-type,accept,cache-control,last-event-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString(method)))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("last-event-id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:5500", "http://127.0.0.1:5501",
            "http://localhost:5500", "http://localhost:5501"})
    void preservesAllLocalOrigins(String origin) throws Exception {
        mvc.perform(options(PATH).header("Origin", origin)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test
    void rejectsUnknownOrigin() throws Exception {
        mvc.perform(options(PATH).header("Origin", "https://untrusted.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void doesNotEnableCorsOutsideApi() throws Exception {
        mvc.perform(options("/actuator/health").header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void nicknameReadStillWorksWithCors() throws Exception {
        mvc.perform(get(PATH).header("Origin", ORIGIN).with(jwt().jwt(jwt -> jwt.subject(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.userId").value(USER_ID))
                .andExpect(jsonPath("$.nicknameSet").value(false));
    }

    @Test
    void nicknameUpdateStillWorksWithCors() throws Exception {
        mvc.perform(put(PATH).header("Origin", ORIGIN).with(jwt().jwt(jwt -> jwt.subject(USER_ID)))
                        .contentType("application/json").content("{\"nickname\":\"테스터\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(jsonPath("$.nickname").value("테스터"));
        verify(jdbcTemplate).update(anyString(), eq(UUID.fromString(USER_ID)), eq("테스터"));
    }
}
