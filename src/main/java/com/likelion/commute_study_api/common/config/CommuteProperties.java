package com.likelion.commute_study_api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "commute")
public class CommuteProperties {

	private int minMinutes = 10;
	private int maxMinutes = 120;
}
