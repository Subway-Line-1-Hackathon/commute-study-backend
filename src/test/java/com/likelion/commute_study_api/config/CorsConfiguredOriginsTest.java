package com.likelion.commute_study_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CorsConfig.class)
@TestPropertySource(properties =
        "app.cors.allowed-origins=https://frontend.example.com,https://preview.example.com")
class CorsConfiguredOriginsTest {

    @Autowired
    UrlBasedCorsConfigurationSource source;

    @Test
    void configuredCommaSeparatedOriginsReplaceDefaults() {
        var config = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/test"));
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("https://frontend.example.com")).isEqualTo("https://frontend.example.com");
        assertThat(config.checkOrigin("https://preview.example.com")).isEqualTo("https://preview.example.com");
        assertThat(config.checkOrigin("http://localhost:5500")).isNull();
    }
}
