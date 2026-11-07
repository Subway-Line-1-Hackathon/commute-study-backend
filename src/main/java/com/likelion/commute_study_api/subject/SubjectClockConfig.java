package com.likelion.commute_study_api.subject;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SubjectClockConfig {
    @Bean
    public Clock subjectClock() {
        return Clock.systemUTC();
    }
}

