package com.likelion.commute_study_api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "material")
public class MaterialProperties {

	private int retentionDays = 7;
	private int maxPerUser = 10;
	private int processingTimeoutMinutes = 10;
}
