package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.subject.SubjectDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "과목")
@SecurityRequirement(name = "bearerAuth")
public class SubjectDeletionController {
    private final CurrentUser currentUser;
    private final SubjectDeletionService service;

    public SubjectDeletionController(CurrentUser currentUser, SubjectDeletionService service) {
        this.currentUser = currentUser;
        this.service = service;
    }

    @Operation(summary = "과목 삭제", description = "과목과 자료를 소프트 삭제합니다. 기존 학습 기록은 유지됩니다. "
            + "204는 삭제 표시와 원본 정리 예약의 커밋 완료이며, Storage 삭제 완료를 뜻하지 않습니다. "
            + "이미 삭제한 본인 과목은 204입니다.")
    @ApiResponse(responseCode = "204", description = "삭제 및 원본 정리 예약 완료", content = @Content)
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: 인증 필요")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 과목")
    @ApiResponse(responseCode = "409", description = "STATE_CONFLICT: UPLOADED/PROCESSING 자료가 있어 전체 삭제 취소")
    @DeleteMapping("/api/subjects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        service.delete(id, currentUser.authenticatedId(jwt));
    }
}
