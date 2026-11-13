package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.report.ReportService;
import com.likelion.commute_study_api.common.exception.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name="리포트",description="통학 후 요약과 과목 통계")
@RestController
@SecurityRequirement(name="bearerAuth")
public class ReportController {
    private final ReportService service;
    public ReportController(ReportService service) { this.service=service; }

    @Operation(summary="세션 전체 학습 결과와 AI 피드백 상태",description="본인 세션의 모든 과목을 합산합니다. answered/solved는 채점 대기 포함, accuracy는 채점 완료 답안 기준(없으면 null)입니다. 기존 minutes/solved/wrong도 제공합니다. PENDING일 때만 다시 조회하세요. READY: 피드백 저장 성공, SKIPPED: 새로 시작·다음 날 종료·채점 답안 없음 또는 기존 종료 세션 소급 생성 생략, FAILED: AI 오류·빈 응답·작업 제출 실패. 조회는 피드백을 생성하거나 상태를 변경하지 않습니다.")
    @ApiResponse(responseCode="200",description="세션 전체 결과",content=@Content(mediaType="application/json",
            schema=@Schema(implementation=ReportService.SessionResult.class),examples=@ExampleObject(name="session",
            value="{\"sessionId\":42,\"today\":{\"answered\":30,\"correct\":24,\"accuracy\":0.8,\"minutes\":24,\"solved\":30,\"wrong\":6},\"feedback\":null,\"feedbackStatus\":\"PENDING\"}")))
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR: 세션 ID 형식 오류",content=@Content)
    @ApiResponse(responseCode="401",description="Bearer JWT 인증 필요",content=@Content)
    @ApiResponse(responseCode="404",description="NOT_FOUND: 없거나 본인 소유가 아닌 세션",content=@Content)
    @GetMapping("/api/sessions/{id}/report")
    public ReportService.SessionResult sessionReport(@Parameter(description="세션 ID") @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt) {
        return service.session(id,jwt);
    }

    @Operation(summary="통학 후 학습 리포트",description="scope=session&sessionId=12: 세션 요약. scope=all: 과목 통계. 정답률은 채점 완료 응답 기준이며 날짜는 KST입니다. answers와 최소 응답 기준에는 채점 대기를 포함합니다.")
    @ApiResponse(responseCode="200",description="학습 결과",content=@Content(mediaType="application/json",
            schema=@Schema(oneOf={ReportService.SessionReport.class,ReportService.SubjectReport.class}),examples={
            @ExampleObject(name="session",value="{\"today\":{\"minutes\":24,\"solved\":12,\"accuracy\":0.75,\"wrong\":3},\"feedback\":\"스택과 큐를 복습해 보세요.\"}"),
            @ExampleObject(name="all",value="{\"accuracyTrend\":[{\"date\":\"2026-11-08\",\"accuracy\":0.8,\"answers\":5}],\"byCategory\":[{\"id\":1,\"accuracy\":0.8},{\"id\":2,\"accuracy\":null}],\"average\":0.8}")}))
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR: scope 또는 sessionId 오류",content=@Content)
    @ApiResponse(responseCode="401",description="JWT 인증 필요",content=@Content)
    @ApiResponse(responseCode="404",description="NOT_FOUND: 과목·세션 소유권 또는 과목 연결 없음",content=@Content)
    @GetMapping("/api/subjects/{id}/report")
    public ReportService.Report report(@PathVariable long id,
            @Parameter(schema=@Schema(allowableValues={"session","all"}),required=true) @RequestParam String scope,
            @Parameter(description="scope=session에서 필수인 양수 세션 ID") @RequestParam(required=false) Long sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        if(sessionId!=null && sessionId<=0) throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
        return switch(scope) {
            case "session" -> {
                if(sessionId==null) throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
                yield service.session(id,sessionId,jwt);
            }
            case "all" -> service.all(id,jwt);
            default -> throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
        };
    }
}
