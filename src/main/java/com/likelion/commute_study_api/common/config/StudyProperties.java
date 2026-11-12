package com.likelion.commute_study_api.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "study")
public class StudyProperties {

	private int curriculumMinMinutes = 11;
	private int summaryMinutes = 3;
	private int maxQuestionsPerSession = 30;

	// 키: ox/mc/short. "short"는 자바 예약어라 필드로 못 둬서 Map으로 받는다.
	private Map<String, Double> minutesPerQuestion = new LinkedHashMap<>(Map.of(
			"ox", 0.5,
			"mc", 1.0,
			"short", 1.5
	));
}
