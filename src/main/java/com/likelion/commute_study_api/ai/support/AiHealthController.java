package com.likelion.commute_study_api.ai.support;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 서버 내부망 연결을 눈으로 확인하기 위한 엔드포인트.
 * actuator/health와 분리해둔다 — AI 서버가 잠깐 죽어도 우리 백엔드 자체는 건강하다고 봐야 해서,
 * 이 결과가 actuator의 전체 상태에 섞여 들어가면 안 된다(Railway가 오해해서 재시작시킬 수 있음).
 */
@RestController
@RequestMapping("/api/ai")
public class AiHealthController {

	private final AiHttpSupport aiHttpSupport;

	public AiHealthController(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@GetMapping("/health")
	public Map<String, Boolean> health() {
		return Map.of("healthy", aiHttpSupport.health());
	}
}
