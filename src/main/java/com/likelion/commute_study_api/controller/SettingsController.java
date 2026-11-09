package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.SettingsRequest;
import com.likelion.commute_study_api.user.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "사용자", description = "내 정보, 닉네임·통학 설정")
@RestController
@RequestMapping("/api/users/me/settings")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "인증 실패", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증에 실패했습니다.\",\"details\":null}")))
public class SettingsController {
    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @Operation(summary = "닉네임 및 통학 설정 저장")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", examples = @ExampleObject(value = """
            {"nickname":"동현","oneWayMin":50,"fromStation":"수원역","toStation":"서울역"}
            """)))
    @PutMapping
    @ApiResponse(responseCode = "200", description = "설정 저장 성공")
    @ApiResponse(responseCode = "400", description = "입력 형식 또는 범위 오류", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"code\":\"VALIDATION_ERROR\",\"message\":\"입력 형식 또는 범위가 올바르지 않습니다.\",\"details\":null}")))
    public SettingsService.SavedSettings save(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody SettingsRequest request) {
        return service.save(jwt, request);
    }

    @Operation(summary = "내 통학 설정 조회", description = "설정이 없으면 404 NOT_FOUND를 반환합니다.")
    @GetMapping
    @ApiResponse(responseCode = "200", description = "설정 조회 성공")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 아직 통학 설정을 저장하지 않음", content = @Content)
    public SettingsService.Settings get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt);
    }
}
