package com.likelion.commute_study_api.session.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest;
import com.likelion.commute_study_api.session.dto.SaveAttemptsResponse;
import com.likelion.commute_study_api.session.service.AttemptService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "답안", description = "답안 저장과 단답형 판정")
@RestController
@RequestMapping("/api/attempts")
@SecurityRequirement(name = "bearerAuth")
public class AttemptController {

	private final AttemptService attemptService;

	public AttemptController(AttemptService attemptService) {
		this.attemptService = attemptService;
	}

	@Operation(summary = "답안 일괄 저장", description = """
			앱이 기기에 쌓아 둔 응답을 한 번에 보낸다(오프라인이었다가 연결되면 전송). 한 트랜잭션으로 저장한다.

			- 같은 세션·문제의 응답이 이미 있으면 건너뛴다. 같은 내용을 다시 보내도 안전하다(`saved`에 세지 않음)
			- `answer` 형식은 세션 응답의 `answer`와 같다: O/X는 `"O"`/`"X"`, 객관식은 선택지 번호 `"1"`~`"4"`, 단답은 입력한 글자
			- O/X·객관식은 서버가 다시 채점하므로 `correct`를 보내지 않아도 된다
			- 단답은 판정 API 결과나 자가 판정을 `correct`에 넣는다. 자가 판정이면 `selfJudged: true`, 판정 전이면 `correct`를 비운다
			- 틀린 문제는 오답노트에 쌓이고, 오답노트 다시 풀기 세션에서 맞히면 해결 처리된다
			- 세션의 모든 문제에 응답하면 `sessionStatus`가 `COMPLETED`가 된다. 이때 통학 후 AI 피드백을 서버가 따로 만든다(세션 종료 API 설명 참고)
			- 이미 끝난(ENDED) 세션에 늦게 도착한 응답도 저장한다
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"sessionId":12,"attempts":[
			  {"questionId":101,"answer":"2","answeredAt":"2026-11-08T08:05:00+09:00"},
			  {"questionId":102,"answer":"push","correct":true,"selfJudged":true,"answeredAt":"2026-11-08T08:06:30+09:00"}
			]}
			""")))
	@ApiResponse(responseCode = "200", description = "저장됨", content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"saved":2,"progress":{"answered":9,"total":27},"sessionStatus":"IN_PROGRESS"}
			""")))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 필수값 없음, attempts 0개·100개 초과, 답 500자 초과",
			content = @Content)
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 세션, 세션에 없는 문제(`details.questionIds`)",
			content = @Content)
	@ApiResponse(responseCode = "409", description = "STATE_CONFLICT: 출발하지 않은(READY) 세션", content = @Content)
	@PostMapping
	public SaveAttemptsResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SaveAttemptsRequest request) {
		return attemptService.save(jwt, request);
	}
}
