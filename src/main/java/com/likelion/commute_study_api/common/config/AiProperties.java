package com.likelion.commute_study_api.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

	private String serverUrl;
	private String internalKey;
	private int maxConcurrency = 3;
	private int signedUrlTtlMinutes = 10;
	private int questionDailyLimit = 20;

	/**
	 * 개발·데모용. true이면 사용자 요청의 X-Mock-Error 헤더를 AI 서버로 그대로 넘겨 AI mock의 실패 응답을 시험한다
	 * (AI 서버 ↔ 백엔드 API 명세 1장). AI 서버가 AI_MOCK=false이면 이 헤더를 무시한다. 운영에서는 끈다.
	 */
	private boolean mockErrorPassthrough = false;

	// 키: health/ai01/ai02/ai02-extra/ai03/ai05/ai06 (초 단위).
	// ai02/ai02-extra는 AI 서버 v0.3 기준 60초(서버 시간 상한 55초)로, 명세서 5장의 300/90초보다 최신값이다(슬라이스4 HITL).
	private Map<String, Integer> timeout = new LinkedHashMap<>(Map.of(
			"health", 5,
			"ai01", 300,
			"ai02", 60,
			"ai02-extra", 60,
			"ai03", 5,
			"ai05", 15,
			"ai06", 15
	));
}
