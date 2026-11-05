
package com.likelion.commute_study_api.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        // 로컬 로그인 테스트 페이지 주소
        config.setAllowedOrigins(List.of(
            "http://127.0.0.1:5500",
            "http://127.0.0.1:5501",
            "http://localhost:5500",
            "http://localhost:5501"
        ));

        // 닉네임 조회 및 변경 요청
        config.setAllowedMethods(List.of(
            "GET",
            "PUT",
            "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type"
        ));

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/api/**", config);

        return source;
    }
}
