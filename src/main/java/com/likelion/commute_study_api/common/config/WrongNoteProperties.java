package com.likelion.commute_study_api.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "wrongnote")
public class WrongNoteProperties {

	private int maxOpen = 100;
	private int maxResolved = 50;
}
