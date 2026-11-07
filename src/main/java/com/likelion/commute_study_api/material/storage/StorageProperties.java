package com.likelion.commute_study_api.material.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private String url = "";
    private String bucket = "";
    private String key = "";
    private String expectedHost = "";
}

