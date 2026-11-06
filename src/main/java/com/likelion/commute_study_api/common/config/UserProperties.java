package com.likelion.commute_study_api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "user")
public class UserProperties {

	private int nicknameMin = 2;
	private int nicknameMax = 10;
}
