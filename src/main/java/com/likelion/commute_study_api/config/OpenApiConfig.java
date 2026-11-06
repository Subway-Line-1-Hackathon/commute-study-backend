package com.likelion.commute_study_api.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Supabase Auth Access Token을 입력하세요. Bearer 접두사는 자동으로 추가됩니다."
)
public class OpenApiConfig {
}
