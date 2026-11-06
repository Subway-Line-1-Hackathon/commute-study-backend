package com.likelion.commute_study_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CommuteStudyApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommuteStudyApiApplication.class, args);
	}

}
