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
	private int pregenRefillMax = 2;
	private int maxPerCategoryDifficulty = 60;

	// 실패 단원 재시도 스케줄러. 팀 공용 DB를 쓰므로 기본은 꺼짐, Railway 환경변수로만 켠다.
	private boolean pregenRetryEnabled = false;
	private Duration pregenRetryCheckInterval = Duration.ofMinutes(5);
	private Duration pregenRetryDelay = Duration.ofMinutes(10);
	private int pregenRetryMax = 1;

	// 난이도(1=쉬움, 2=보통, 3=어려움)별 유형(ox/mc/short) 비율.
	// 단원당 총량 40개는 유지하고 난이도 비율을 30/40/30으로 나눈 값이다(#88, 추정값 — 실사용 데이터로 조정 가능).
	private Map<Integer, Map<String, Integer>> pregenRatio = defaultPregenRatio();

	private static Map<Integer, Map<String, Integer>> defaultPregenRatio() {
		Map<Integer, Map<String, Integer>> byDifficulty = new LinkedHashMap<>();
		byDifficulty.put(1, typeRatio(4, 4, 4));
		byDifficulty.put(2, typeRatio(6, 5, 5));
		byDifficulty.put(3, typeRatio(4, 4, 4));
		return byDifficulty;
	}

	private static Map<String, Integer> typeRatio(int ox, int mc, int shortCount) {
		Map<String, Integer> ratio = new LinkedHashMap<>();
		ratio.put("ox", ox);
		ratio.put("mc", mc);
		ratio.put("short", shortCount);
		return ratio;
	}
}
