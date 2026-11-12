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

    @Operation(summary = "강의자료 업로드", description = """
            PDF/PPTX 1개, 또는 같은 `file` 필드로 이미지(PNG·JPG) 1~20장. kind=LECTURE.
            - 이미지는 전송 순서가 쪽 순서다. PNG와 JPG를 섞어 보내도 된다(스크린샷과 폰 사진)
            - JPG는 `.jpg`·`.jpeg`(`image/jpeg`). 폰 사진의 회전 정보는 AI 서버가 반영한다
            - iPhone HEIC는 받지 않는다. 앱에서 JPG로 바꿔 올린다
            - 파일당 최대 30MiB. PDF·PPT 100쪽 초과는 AI 서버가 판단해 자료가 `FAILED`가 된다""")
    @ApiResponse(responseCode = "201", description = "저장 완료. 단원화(AI-01)는 뒤에서 진행되므로 상태 조회 API로 확인한다",
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
            {"materialId":8,"status":"UPLOADED"}
            """)))
    @ApiResponse(responseCode = "400", description = """
            VALIDATION_ERROR: 파일 없음·빈 파일, 이미지 20장 초과, 이미지가 아닌 파일을 여러 개 보냄, kind가 LECTURE가 아님 /
            UNSUPPORTED_FILE_TYPE: PDF·PPTX·PNG·JPG가 아닌 파일, 확장자와 Content-Type 불일치(HEIC 포함)""", content = @Content)
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

    @Operation(summary = "내 자료 목록 조회", description = "계정의 모든 자료를 `createdAt` 내림차순으로 반환합니다. 과목과 무관하게 본인 자료만 조회합니다.")
    @ApiResponse(responseCode = "200", description = "자료 목록", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
            {"materials":[{"id":9,"subjectId":2,"fileName":"강의.pdf","status":"READY","createdAt":"2026-11-08T10:30:00+09:00"},{"id":8,"subjectId":2,"fileName":"1강.pptx","status":"PROCESSING","createdAt":"2026-11-07T09:00:00+09:00"}]}
            """)))
    @GetMapping("/api/materials")
    public MaterialService.MaterialList list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
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
