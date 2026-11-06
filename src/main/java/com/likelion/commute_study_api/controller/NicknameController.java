
package com.likelion.commute_study_api.controller;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me")
public class NicknameController {

    private final JdbcTemplate jdbcTemplate;

    public NicknameController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 닉네임 설정 및 수정
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = "application/json",
                    examples = @ExampleObject(
                            value = "{\"nickname\":\"동현\"}"
                    )
            )
    )
    @PutMapping("/nickname")
    public ResponseEntity<?> updateNickname(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody Map<String, String> request) {

        // JWT에서 인증된 사용자의 고유 UUID 추출
        UUID userId = UUID.fromString(jwt.getSubject());

        String nickname = request.get("nickname");

        if (nickname == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "닉네임을 입력해주세요."));
        }

        nickname = nickname.trim();

        int length = nickname.codePointCount(0, nickname.length());

        if (length < 2 || length > 10) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "닉네임은 2~10자로 입력해주세요."));
        }

        // UUID를 기준으로 사용자 생성 또는 닉네임 수정
        jdbcTemplate.update("""
            INSERT INTO public.app_user (id, nickname)
            VALUES (?, ?)
            ON CONFLICT (id)
            DO UPDATE SET nickname = EXCLUDED.nickname
            """, userId, nickname);

        return ResponseEntity.ok(Map.of(
                "userId", userId.toString(),
                "nickname", nickname,
                "message", "닉네임 저장 성공"
        ));
    }

    // 현재 로그인한 사용자의 닉네임 조회
    @GetMapping("/nickname")
    public ResponseEntity<?> getNickname(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        String nickname = jdbcTemplate.query("""
            SELECT nickname
            FROM public.app_user
            WHERE id = ?
            """,
            rs -> rs.next() ? rs.getString("nickname") : null,
            userId
        );

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId.toString());
        response.put("nickname", nickname);
        response.put("nicknameSet", nickname != null && !nickname.isBlank());

        return ResponseEntity.ok(response);
    }
}
