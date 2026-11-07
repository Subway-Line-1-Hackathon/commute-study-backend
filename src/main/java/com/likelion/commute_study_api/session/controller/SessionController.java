package com.likelion.commute_study_api.session.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.likelion.commute_study_api.session.dto.CreateSessionRequest;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.service.SessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "세션", description = "오늘의 커리큘럼(학습 세션)")
@RestController
@RequestMapping("/api/sessions")
@SecurityRequirement(name = "bearerAuth")
public class SessionController {

	private final SessionService sessionService;

	public SessionController(SessionService sessionService) {
		this.sessionService = sessionService;
	}

	@Operation(summary = "오늘의 커리큘럼 생성", description = """
			단원·유형·난이도를 고르면 통학 시간에 맞는 문제 수로 세션을 만든다. 오답노트 다시 풀기(`source: wrongNotes`)와
			'이어서 더 풀기'(`studyMinOverride`에 남은 시간, `skipSummary: true`)도 이 API를 쓴다.

			- 문제 수: `min(30, floor((학습 시간 - 요약 3분) / 선택 유형 평균 시간))`. 요약은 CURRICULUM에서만 뺀다
			- 학습 시간이 20분 미만이면 REVIEW(오답 → 이미 푼 문제, 요약 없음)
			- 문제가 모자라면 AI로 부족분을 만든다. **최대 약 60초** 걸릴 수 있으니 대기 화면을 보여준다
			- 그래도 모자라면 `shortage`가 온다. REVIEW인데 푼 문제가 없으면 `noReviewItems: true`(세션 없음)
			- O/X·객관식은 `answer`·`explanation`을 담고, 단답은 담지 않는다
			- 출발하지 않은 이전 READY 세션은 지운다
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = "application/json",
			examples = {
					@ExampleObject(name = "단원 선택", value = """
							{"source":"categories","categoryIds":[7],"questionTypes":["OX","MC"],"difficulty":2}
							"""),
					@ExampleObject(name = "이어서 더 풀기", value = """
							{"source":"categories","categoryIds":[7],"questionTypes":["MC"],"difficulty":2,"studyMinOverride":12,"skipSummary":true}
							"""),
					@ExampleObject(name = "오답노트 다시 풀기", value = """
							{"source":"wrongNotes","questionTypes":["OX","MC"],"difficulty":2}
							""")
			}))
	@ApiResponse(responseCode = "201", description = "세션 생성", content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"sessionId":12,"mode":"CURRICULUM","studyMin":30,"questionCount":2,
			 "summary":"- 스택은 LIFO, 큐는 FIFO",
			 "shortage":{"target":27,"available":2,"allSolved":false},
			 "questions":[
			   {"id":101,"type":"MC","stem":"큐의 특징은?","choices":["LIFO","FIFO"],"sourcePage":2,"answer":"FIFO","explanation":"큐는 먼저 넣은 것이 먼저 나온다."},
			   {"id":102,"type":"SHORT","stem":"스택의 삽입 연산 이름은?","sourcePage":1}
			 ]}
			""")))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 필수값·범위 오류, 모르는 source, studyMinOverride 120분 초과")
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 단원")
	@ApiResponse(responseCode = "422", description = """
			SETUP_REQUIRED: 닉네임·통학 설정·과목 없음 /
			CATEGORY_NOT_READY: 문제 준비 중(PREPARING) 단원 포함(`details.categoryIds`) /
			NO_QUESTIONS_AVAILABLE: 고를 문제가 0개""")
	@ApiResponse(responseCode = "502", description = "AI_UNAVAILABLE: 추가 생성이 실패했고 있는 문제도 0개")
	@ApiResponse(responseCode = "504", description = "AI_TIMEOUT: 추가 생성이 시간 안에 끝나지 않았고 있는 문제도 0개")
	@ResponseStatus(HttpStatus.CREATED)
	@PostMapping
	public CreateSessionResponse create(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateSessionRequest request) {
		return sessionService.create(jwt, request);
	}
}
