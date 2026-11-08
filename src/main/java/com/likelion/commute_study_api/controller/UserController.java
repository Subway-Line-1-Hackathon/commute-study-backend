package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.UserResponse;
import com.likelion.commute_study_api.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
@ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증에 실패했습니다.\",\"details\":null}")))
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회", description = "최초 호출 시 닉네임 없는 사용자를 자동 생성합니다.")
    @GetMapping("/me")
    @ApiResponse(responseCode = "200", description = "내 정보 조회 성공")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getMe(jwt);
    }
}
