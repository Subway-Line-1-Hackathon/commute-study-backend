package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.wrongnote.WrongNoteService;
import com.likelion.commute_study_api.common.exception.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name="오답노트",description="과목별 오답 목록")
@RestController
@SecurityRequirement(name="bearerAuth")
public class WrongNoteController {
    private final WrongNoteService service;
    public WrongNoteController(WrongNoteService service) { this.service=service; }
    @Operation(summary="과목별 오답노트",description="최근 오답 시각, questionId 내림차순. 과목별 OPEN/RESOLVED 상한(기본 100/50) 적용 후 단원 필터를 적용합니다. 초과 행은 삭제하지 않습니다. 재풀이는 POST /api/sessions의 source: wrongNotes를 사용합니다.")
    @ApiResponse(responseCode="200",description="오답 목록 또는 빈 items",content=@Content(mediaType="application/json",examples={
            @ExampleObject(name="notes",value="{\"items\":[{\"questionId\":176,\"categoryId\":11,\"stem\":\"스택의 특징은?\",\"wrongCount\":3,\"status\":\"OPEN\",\"lastWrongAt\":\"2026-11-08T13:30:00Z\"},{\"questionId\":177,\"categoryId\":11,\"stem\":\"큐의 특징은?\",\"wrongCount\":2,\"status\":\"RESOLVED\",\"lastWrongAt\":\"2026-11-07T15:20:00Z\"}]}"),
            @ExampleObject(name="empty",value="{\"items\":[]}")}))
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR: 잘못된 categoryId",content=@Content)
    @ApiResponse(responseCode="401",description="JWT 인증 필요",content=@Content)
    @ApiResponse(responseCode="404",description="NOT_FOUND: 과목 또는 단원 접근 불가",content=@Content)
    @GetMapping("/api/subjects/{id}/wrong-notes")
    public WrongNoteService.Response list(@PathVariable long id,
            @Parameter(description="선택적 양수 단원 ID. 요청 과목에 속해야 합니다.",schema=@Schema(type="integer",format="int64",minimum="1")) @RequestParam(required=false) String categoryId,
            @AuthenticationPrincipal Jwt jwt) {
        Long parsed=null;
        if(categoryId!=null) {
            try { parsed=Long.valueOf(categoryId); }
            catch(NumberFormatException e) { throw new ApiException(CommonErrorCode.VALIDATION_ERROR); }
            if(parsed<=0) throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
        }
        return service.list(id,parsed,jwt);
    }
}
