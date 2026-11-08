package com.likelion.commute_study_api.ai.support;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * AI 서버 내부망 연결을 눈으로 확인하기 위한 엔드포인트.
 * actuator/health와 분리해둔다 — AI 서버가 잠깐 죽어도 우리 백엔드 자체는 건강하다고 봐야 해서,
 * 이 결과가 actuator의 전체 상태에 섞여 들어가면 안 된다(Railway가 오해해서 재시작시킬 수 있음).
 */
@Tag(name = "개발·점검", description = "배포 환경 연결 확인용")
@RestController
@RequestMapping("/api/ai")
@SecurityRequirement(name = "bearerAuth")
public class AiHealthController {

	private final AiHttpSupport aiHttpSupport;

	public AiHealthController(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@Operation(summary = "AI 서버 연결 확인", description = """
			백엔드가 AI 서버(`AI_SERVER_URL`)의 `/health`에 닿는지 확인한다(최대 약 5초).
			AI 서버가 응답하지 않아도 에러가 아니라 `{"healthy": false}`(200)다. 백엔드 자체 상태는 `/actuator/health`로 본다.
			""")
	@ApiResponse(responseCode = "200", description = "연결 결과", content = @Content(
			mediaType = "application/json", examples = {
					@ExampleObject(name = "연결됨", value = """
							{"healthy":true}
							"""),
					@ExampleObject(name = "연결 안 됨", value = """
							{"healthy":false}
							""")
			}))
	@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: JWT 인증 필요", content = @Content)
	@GetMapping("/health")
	public Map<String, Boolean> health() {
		return Map.of("healthy", aiHttpSupport.health());
	}
}
