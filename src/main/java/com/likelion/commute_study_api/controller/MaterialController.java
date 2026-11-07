package com.likelion.commute_study_api.controller;

import java.util.List;
import com.likelion.commute_study_api.material.MaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class MaterialController {
    private final MaterialService service;
    public MaterialController(MaterialService service) { this.service = service; }

    @Operation(summary = "강의자료 업로드", description = "PDF/PPTX 1개 또는 같은 file 필드로 PNG 1~20장. 전송 순서가 쪽 순서이며 파일당 최대 30MiB. kind=LECTURE.")
    @PostMapping(value = "/api/subjects/{id}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialService.Uploaded upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                           @RequestParam("file") List<MultipartFile> files, @RequestParam String kind) {
        return service.upload(jwt, id, files, kind);
    }

    @Operation(summary = "내 자료 처리 상태 조회")
    @GetMapping("/api/materials/{id}/status")
    public MaterialService.Status status(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.status(jwt, id);
    }

    @Operation(summary = "실패한 자료 재시도", description = "FAILED만 허용하며 만료되었거나 다른 상태이면 409입니다.")
    @PostMapping("/api/materials/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialService.Uploaded retry(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.retry(jwt, id);
    }
}

