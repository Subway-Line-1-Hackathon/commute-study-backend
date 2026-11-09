package com.likelion.commute_study_api.controller;

import java.util.List;
import com.likelion.commute_study_api.material.MaterialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "자료", description = "강의자료 업로드와 단원화 처리 상태")
@RestController
@SecurityRequirement(name = "bearerAuth")
public class MaterialController {
    private final MaterialService service;
    public MaterialController(MaterialService service) { this.service = service; }

    @Operation(summary = "강의자료 업로드", description = "PDF/PPTX 1개 또는 같은 file 필드로 PNG 1~20장. 전송 순서가 쪽 순서이며 파일당 최대 30MiB. kind=LECTURE.")
    @ApiResponse(responseCode = "201", description = "저장 완료. 단원화(AI-01)는 뒤에서 진행되므로 상태 조회 API로 확인한다",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
            {"materialId":8,"status":"UPLOADED"}
            """)))
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_ERROR: 파일 없음·빈 파일, PNG 20장 초과, PNG가 아닌 여러 파일, kind가 LECTURE가 아님 /
            UNSUPPORTED_FILE_TYPE: PDF·PPTX·PNG가 아닌 파일""", content = @Content)
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 과목", content = @Content)
    @ApiResponse(responseCode = "413", description = "FILE_TOO_LARGE: 파일 하나가 30MiB 초과", content = @Content)
    @ApiResponse(responseCode = "502", description = "STORAGE_UNAVAILABLE: 자료 저장소(Supabase Storage)에 접근할 수 없음",
            content = @Content)
    @PostMapping(value = "/api/subjects/{id}/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialService.Uploaded upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                           @RequestParam("file") List<MultipartFile> files, @RequestParam String kind) {
        return service.upload(jwt, id, files, kind);
    }

    @Operation(summary = "내 자료 처리 상태 조회", description = """
            업로드 후 몇 초 간격으로 폴링한다. `UPLOADED` → `PROCESSING` → `READY` 또는 `FAILED`.
            `FAILED`일 때만 `error`에 사유(AI 서버 메시지)가 온다. 보관 기간이 지나면 `EXPIRED`.""")
    @ApiResponse(responseCode = "200", description = "처리 상태", content = @Content(
            mediaType = "application/json", examples = {
                    @ExampleObject(name = "완료", value = """
                            {"status":"READY","categoryCount":5}
                            """),
                    @ExampleObject(name = "실패", value = """
                            {"status":"FAILED","categoryCount":0,"error":"지원하지 않는 파일입니다."}
                            """)
            }))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 자료", content = @Content)
    @GetMapping("/api/materials/{id}/status")
    public MaterialService.Status status(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.status(jwt, id);
    }

    @Operation(summary = "실패한 자료 재시도", description = "FAILED만 허용하며 만료되었거나 다른 상태이면 409입니다.")
    @ApiResponse(responseCode = "202", description = "재처리 시작", content = @Content(
            mediaType = "application/json", examples = @ExampleObject(value = """
            {"materialId":8,"status":"PROCESSING"}
            """)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 자료", content = @Content)
    @ApiResponse(responseCode = "409", description = "STATE_CONFLICT: FAILED가 아니거나 만료된 자료", content = @Content)
    @PostMapping("/api/materials/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MaterialService.Uploaded retry(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return service.retry(jwt, id);
    }
}
