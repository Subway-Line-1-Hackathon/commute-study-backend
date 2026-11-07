package com.likelion.commute_study_api.common.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "question")
public class QuestionProperties {

	private int pregenTotal = 40;
	private int pregenDifficulty = 2;
	private int pregenRefillMax = 2;
	private int maxPerCategoryDifficulty = 60;

	// 실패 단원 재시도 스케줄러. 팀 공용 DB를 쓰므로 기본은 꺼짐, Railway 환경변수로만 켠다.
	private boolean pregenRetryEnabled = false;
	private Duration pregenRetryCheckInterval = Duration.ofMinutes(5);
	private Duration pregenRetryDelay = Duration.ofMinutes(10);
	private int pregenRetryMax = 1;

	// 키: ox/mc/short
	private Map<String, Integer> pregenRatio = new LinkedHashMap<>(Map.of(
			"ox", 14,
			"mc", 13,
			"short", 13
	));
}
