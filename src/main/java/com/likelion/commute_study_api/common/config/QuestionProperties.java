package com.likelion.commute_study_api.common.config;

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

	// 키: ox/mc/short
	private Map<String, Integer> pregenRatio = new LinkedHashMap<>(Map.of(
			"ox", 14,
			"mc", 13,
			"short", 13
	));
}
