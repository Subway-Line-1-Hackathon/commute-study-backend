package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.subject.SubjectRequest;
import com.likelion.commute_study_api.subject.SubjectPatchRequest;
import com.likelion.commute_study_api.subject.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "과목", description = "과목 등록, 수정과 목록")
@RestController
@RequestMapping("/api/subjects")
@SecurityRequirement(name = "bearerAuth")
public class SubjectController {
    private final SubjectService service;

    public SubjectController(SubjectService service) {
        this.service = service;
    }

    @Operation(summary = "과목 등록")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json",
            examples = @ExampleObject(value = "{\"name\":\"선형대수\",\"examDate\":\"2026-12-15\"}")))
    @ApiResponse(responseCode = "201", description = "생성 완료", content = @Content(
            mediaType = "application/json", examples = @ExampleObject(value = "{\"subjectId\":1}")))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 과목명 또는 시험일 형식 오류")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping
    public SubjectService.CreatedSubject create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody SubjectRequest request) {
        return service.create(jwt, request);
    }

    @Operation(summary = "과목 수정", description = "포함된 필드만 수정합니다. examDate를 null로 보내면 시험일을 삭제합니다. D-Day는 KST 기준입니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = "application/json", examples = {
                    @ExampleObject(name = "과목명과 시험일 수정", value = "{\"name\":\"운영체제\",\"examDate\":\"2026-12-15\"}"),
                    @ExampleObject(name = "시험일 삭제", value = "{\"examDate\":null}")
            }))
    @ApiResponse(responseCode = "200", description = "수정 완료", content = @Content(
            mediaType = "application/json", examples = @ExampleObject(value = "{\"id\":3,\"name\":\"운영체제\",\"examDate\":\"2026-12-15\",\"dDay\":32}")))
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 과목명 또는 시험일 형식 오류")
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: 인증 필요")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 과목")
    @PatchMapping("/{id}")
    public SubjectService.UpdatedSubject update(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id,
                                                 @Valid @RequestBody SubjectPatchRequest request) {
        return service.update(jwt, id, request);
    }

    @Operation(summary = "내 과목 목록", description = "ID 오름차순. D-Day는 요청 시점 KST 날짜 기준입니다.")
    @ApiResponse(responseCode = "200", description = "본인 과목 목록", content = @Content(
            mediaType = "application/json", examples = @ExampleObject(value = """
            {"subjects":[{"id":1,"name":"선형대수","examDate":"2026-12-15","dDay":38},{"id":2,"name":"프로그래밍"}]}
            """)))
    @GetMapping
    public SubjectService.SubjectList list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt);
    }
}

