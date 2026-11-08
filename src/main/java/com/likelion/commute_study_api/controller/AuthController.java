package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자", description = "내 정보, 닉네임·통학 설정")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/auth")
@ApiResponse(responseCode="401",description="인증 실패",content=@Content(mediaType="application/json",examples=@ExampleObject(value="{\"code\":\"UNAUTHORIZED\",\"message\":\"인증에 실패했습니다.\",\"details\":null}")))
public class AuthController {
    private final UserService service;
    public AuthController(UserService service) {
        this.service = service;
    }

    @Operation(summary = "로그인 상태 조회(구버전)", deprecated = true, description = "GET /api/users/me로 전환해주세요. 기존 성공 응답은 유지하며 이 조회는 사용자를 생성하지 않습니다.")
    @ApiResponse(responseCode = "200", description = "로그인 상태 조회 성공")
    @GetMapping("/me")
    public UserService.LegacyAuth me(@AuthenticationPrincipal Jwt jwt) {
        return service.legacyAuth(jwt);
    }
}
