package com.likelion.commute_study_api.session.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.likelion.commute_study_api.session.dto.CreateSessionRequest;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.dto.CurrentSessionResponse;
import com.likelion.commute_study_api.session.dto.EndSessionRequest;
import com.likelion.commute_study_api.session.dto.EndSessionResponse;
import com.likelion.commute_study_api.session.dto.StartSessionResponse;
import com.likelion.commute_study_api.session.service.SessionProgressService;
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
	private final SessionProgressService progressService;

	public SessionController(SessionService sessionService, SessionProgressService progressService) {
		this.sessionService = sessionService;
		this.progressService = progressService;
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
			   {"id":101,"type":"MC","stem":"큐의 특징은?","choices":["LIFO","FIFO"],"sourcePage":2,"answer":"2","explanation":"큐는 먼저 넣은 것이 먼저 나온다."},
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

	@Operation(summary = "이어하기용 진행 중 세션", description = """
			앱을 다시 열었을 때 이어하기 팝업에 쓴다. 진행 중(IN_PROGRESS) 세션이 없으면 204.

			- `answered`는 서버에 저장된 응답 수다. 세션 문제 순서에서 `answered`개 다음 문제부터 이어 푼다
			- 시작한 날(KST)이 지났으면 세션을 `ENDED`로 바꾸고 204를 준다
			- 타이머는 서버가 보관하지 않는다. 이어 풀 때 앱이 새로 시작한다
			""")
	@ApiResponse(responseCode = "200", description = "진행 중 세션", content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"sessionId":12,"categoryNames":["스택","큐"],"answered":7,"total":27}
			""")))
	@ApiResponse(responseCode = "204", description = "진행 중 세션 없음(또는 다음 날이 되어 자동 종료됨)", content = @Content)
	@GetMapping("/current")
	public ResponseEntity<CurrentSessionResponse> current(@AuthenticationPrincipal Jwt jwt) {
		return progressService.current(jwt)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(summary = "세션 시작(출발)", description = """
			READY 세션을 IN_PROGRESS로 바꾸고 시작 시각을 기록한다.
			이미 진행 중인 다른 세션이 있으면 그 세션을 먼저 `ENDED`(새로 시작)로 끝낸다. 사용자당 진행 중 세션은 1개다.
			""")
	@ApiResponse(responseCode = "200", description = "시작됨", content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"status":"IN_PROGRESS","startedAt":"2026-11-08T01:00:00Z"}
			""")))
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 세션", content = @Content)
	@ApiResponse(responseCode = "409", description = "STATE_CONFLICT: READY가 아닌 세션(`details.status`에 현재 상태), 동시에 다른 세션 시작",
			content = @Content)
	@PostMapping("/{id}/start")
	public StartSessionResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id) {
		return progressService.start(jwt, id);
	}

	@Operation(summary = "세션 종료", description = """
			진행 중 세션을 `ENDED`로 바꾼다. `reason`: `QUIT`(그만하기), `ARRIVED`(도착), `NEW_START`(이어하기 팝업에서 새로 시작).
			마지막 문제까지 풀면 답안 저장에서 자동으로 `COMPLETED`가 되므로 이 API를 부르지 않아도 된다.
			이미 끝난 세션이면 그 상태(`COMPLETED`/`ENDED`)를 그대로 준다(다시 보내도 안전).

			**통학 후 AI 피드백**: 종료가 저장된 뒤 서버가 따로 만들어 저장한다(보통 수 초, 최대 약 15초). 이 응답은 기다리지 않는다.
			- 리포트(`GET /api/subjects/{id}/report?scope=session`)의 `feedback`으로 읽는다. 아직 없거나 AI가 실패하면 `null`이니 숫자 카드만 보여준다
			- `NEW_START`로 끝났거나 채점된 응답이 0개면 만들지 않는다. 세션당 한 번만 만든다
			- 종료 전에 기기에 쌓인 응답을 먼저 `POST /api/attempts`로 보내야 피드백에 반영된다
			""")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"reason":"QUIT"}
			""")))
	@ApiResponse(responseCode = "200", description = "종료됨", content = @Content(
			mediaType = "application/json", examples = @ExampleObject(value = """
			{"status":"ENDED"}
			""")))
	@ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: reason 없음·모르는 값", content = @Content)
	@ApiResponse(responseCode = "404", description = "NOT_FOUND: 없거나 다른 사용자의 세션", content = @Content)
	@ApiResponse(responseCode = "409", description = "STATE_CONFLICT: 출발하지 않은(READY) 세션", content = @Content)
	@PostMapping("/{id}/end")
	public EndSessionResponse end(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") long id,
			@Valid @RequestBody EndSessionRequest request) {
		return progressService.end(jwt, id, request.reason());
	}
}
