package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.user.SettingsRequest;
import com.likelion.commute_study_api.user.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/settings")
@SecurityRequirement(name = "bearerAuth")
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
    public SettingsService.SavedSettings save(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody SettingsRequest request) {
        return service.save(jwt, request);
    }

    @Operation(summary = "내 통학 설정 조회", description = "설정이 없으면 404 NOT_FOUND를 반환합니다.")
    @GetMapping
    public SettingsService.Settings get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt);
    }
}
