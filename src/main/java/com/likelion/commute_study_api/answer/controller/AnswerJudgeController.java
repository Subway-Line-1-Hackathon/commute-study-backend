package com.likelion.commute_study_api.answer.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.likelion.commute_study_api.answer.dto.JudgeAnswerRequest;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerResponse;
import com.likelion.commute_study_api.answer.service.AnswerJudgeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "답안", description = "답안 저장과 단답형 판정")
@RestController
@RequestMapping("/api/answers")
@SecurityRequirement(name = "bearerAuth")
public class AnswerJudgeController {

	private final AnswerJudgeService answerJudgeService;

	public AnswerJudgeController(AnswerJudgeService answerJudgeService) {
		this.answerJudgeService = answerJudgeService;
	}

	@Operation(summary = "단답형 판정", description = """
			단답 답안의 맞고 틀림을 판정한다. **판정만 하고 저장하지 않는다.** 결과는 `POST /api/attempts`로 저장한다.

			1. 답을 정규화(NFKC, 소문자, 공백·문장부호 제거)해 정답 목록과 같으면 AI 없이 `{correct: true}`
			2. 공백·문장부호뿐인 답은 AI 없이 `{correct: false}`
			3. 다르면 AI가 판정해 `{correct, reason}` (최대 약 5초)
			4. AI가 늦거나 실패하면 `{correct: null, timeout: true}`(200). 대표 정답을 보여주고 사용자가 맞음·틀림을 고르게 한 뒤,
			   `POST /api/attempts`에 `correct`와 `selfJudged: true`로 저장한다

			`answer`(대표 정답, question.answer)는 세 경우 모두 포함한다.
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"questionId":103,"answer":"대기열"}
			""")))
	@ApiResponse(responseCode = "200", description = "판정 결과", content = @Content(
			mediaType = "application/json", examples = {
					@ExampleObject(name = "정답 목록과 일치", value = """
							{"correct":true,"answer":"대기열"}
							"""),
					@ExampleObject(name = "AI 판정", value = """
							{"correct":true,"reason":"큐를 뜻하는 다른 표현이에요.","answer":"대기열"}
							"""),
					@ExampleObject(name = "AI 실패·시간 초과(자가 판정)", value = """
							{"correct":null,"timeout":true,"answer":"대기열"}
							""")
			}))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 필수값 없음, 답 30자 초과, 단답이 아닌 문제",
			content = @Content)
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 문제", content = @Content)
	// 개발용 X-Mock-Error 입력칸은 설정이 켜진 서버에서만 MockErrorOpenApiCustomizer가 붙인다
	@PostMapping("/judge")
	public JudgeAnswerResponse judge(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody JudgeAnswerRequest request) {
		return answerJudgeService.judge(jwt, request);
	}
}
