package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.material.MaterialDeletionTransactions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="자료")
@RestController
@SecurityRequirement(name="bearerAuth")
public class MaterialDeletionController {
    private final CurrentUser currentUser;
    private final MaterialDeletionTransactions deletion;
    public MaterialDeletionController(CurrentUser currentUser, MaterialDeletionTransactions deletion) {
        this.currentUser=currentUser; this.deletion=deletion;
    }
    @Operation(summary="자료 삭제", description="""
            본인 자료를 소프트 삭제합니다. 204는 논리 삭제와 원본 정리 예약의 DB 커밋을 뜻하며,
            Storage 객체 삭제는 백그라운드에서 재시도됩니다. 학습 기록은 보존합니다.
            이미 삭제한 본인 자료를 다시 요청해도 204입니다.
            """)
    @ApiResponse(responseCode="204", description="논리 삭제 및 원본 정리 예약 완료")
    @ApiResponse(responseCode="401", description="인증 필요")
    @ApiResponse(responseCode="404", description="없거나 다른 사용자의 자료")
    @ApiResponse(responseCode="409", description="업로드 또는 처리 중인 자료")
    @DeleteMapping("/api/materials/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        deletion.delete(id,currentUser.authenticatedId(jwt));
    }
}
