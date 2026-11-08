package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.NicknameRequest;
import com.likelion.commute_study_api.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me")
@ApiResponse(responseCode="401",description="인증 실패",content=@Content(mediaType="application/json",examples=@ExampleObject(value="{\"code\":\"UNAUTHORIZED\",\"message\":\"인증에 실패했습니다.\",\"details\":null}")))
public class NicknameController {
    private final UserService service;
    public NicknameController(UserService service) {
        this.service = service;
    }

    @Operation(deprecated=true,description="PUT /api/users/me/settings로 전환해주세요. 이 경로는 통학 설정 없이 닉네임만 저장합니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",examples=@ExampleObject(value="{\"nickname\":\"동현\"}")))
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR: 닉네임 또는 JSON 형식 오류",content=@Content(mediaType="application/json",examples={
            @ExampleObject(name="nickname",value="{\"code\":\"VALIDATION_ERROR\",\"message\":\"닉네임은 2~10자로 입력해주세요.\",\"details\":null}"),
            @ExampleObject(name="json",value="{\"code\":\"VALIDATION_ERROR\",\"message\":\"입력 형식 또는 범위가 올바르지 않습니다.\",\"details\":null}")}))
    @PutMapping("/nickname")
    @ApiResponse(responseCode = "200", description = "닉네임 저장 성공")
    public UserService.SavedNickname updateNickname(@AuthenticationPrincipal Jwt jwt, @RequestBody NicknameRequest request) {
        return service.saveNickname(jwt, request);
    }

    @Operation(deprecated=true,description="GET /api/users/me/settings로 전환해주세요. 기존 성공 응답은 유지합니다.")
    @GetMapping("/nickname")
    @ApiResponse(responseCode = "200", description = "닉네임 조회 성공")
    public UserService.LegacyNickname getNickname(@AuthenticationPrincipal Jwt jwt) {
        return service.legacyNickname(jwt);
    }
}
