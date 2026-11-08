package com.likelion.commute_study_api.controller;

import com.likelion.commute_study_api.study.StudyReadRepository;
import com.likelion.commute_study_api.study.StudyReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name="학습 현황",description="단원별 진도·정답률과 문제 해설")
@RestController
@SecurityRequirement(name="bearerAuth")
public class StudyReadController {
    private final StudyReadService service;
    public StudyReadController(StudyReadService service) { this.service=service; }

    @Operation(summary="단원별 진도 및 정답률",description="진도는 채점 완료 PREGEN 고유 문제 수/실제 PREGEN 수(0~1). 정답률은 EXTRA·반복 응답·자가 판정을 포함하며 채점 대기는 제외합니다. 단원 순서는 order, id입니다.")
    @ApiResponse(responseCode="200",description="단원 목록 및 추천 단원",content=@Content(mediaType="application/json",examples=@ExampleObject(value="""
            {"categories":[{"id":1,"name":"행렬","pageStart":1,"pageEnd":12,"progress":0.5,"accuracy":0.8,"lastStudiedAt":"2026-11-08T10:30:00+09:00","state":"IN_PROGRESS","questionStatus":"AVAILABLE"}],"recommendedId":1}
            """)))
    @ApiResponse(responseCode="404",description="NOT_FOUND: 존재하지 않거나 다른 사용자의 과목",content=@Content)
    @ApiResponse(responseCode="401",description="UNAUTHORIZED: JWT 인증 필요",content=@Content)
    @GetMapping("/api/subjects/{id}/categories")
    public StudyReadService.CategoryList categories(@PathVariable long id,@AuthenticationPrincipal Jwt jwt) {
        return service.categories(id,jwt);
    }

    @Operation(summary="문제 해설 조회",description="본인 과목의 문제 해설·근거 쪽수만 반환합니다. 원본 자료가 EXPIRED여도 조회할 수 있으며 값이 없는 필드는 null입니다.")
    @ApiResponse(responseCode="200",description="해설 및 근거 쪽수",content=@Content(mediaType="application/json",examples=@ExampleObject(value="""
            {"explanation":"각 성분의 곱을 더합니다.","sourcePage":7}
            """)))
    @ApiResponse(responseCode="404",description="NOT_FOUND: 존재하지 않거나 다른 사용자의 문제",content=@Content)
    @ApiResponse(responseCode="401",description="UNAUTHORIZED: JWT 인증 필요",content=@Content)
    @GetMapping("/api/questions/{id}/explanation")
    public StudyReadRepository.Explanation explanation(@PathVariable long id,@AuthenticationPrincipal Jwt jwt) {
        return service.explanation(id,jwt);
    }
}
