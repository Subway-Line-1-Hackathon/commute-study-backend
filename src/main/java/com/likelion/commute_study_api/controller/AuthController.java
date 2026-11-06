
package com.likelion.commute_study_api.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcTemplate jdbcTemplate;

    public AuthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        String nickname = jdbcTemplate.query("""
                SELECT nickname
                FROM public.app_user
                WHERE id = ?
                """,
                rs -> rs.next() ? rs.getString("nickname") : null,
                userId
        );

        boolean nicknameSet =
                nickname != null && !nickname.isBlank();

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("userId", userId.toString());
        response.put("email", jwt.getClaimAsString("email"));
        response.put("nickname", nickname);
        response.put("nicknameSet", nicknameSet);

        return response;
    }
}
