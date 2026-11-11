package com.likelion.commute_study_api.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

	private int maxFileMb = 30;
	private List<String> allowedTypes = List.of("PDF", "PPTX", "PNG", "JPG");
}
