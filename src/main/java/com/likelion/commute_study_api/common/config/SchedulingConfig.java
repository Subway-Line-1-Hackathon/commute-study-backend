package com.likelion.commute_study_api.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Scheduled 작업 공용 활성화(문제 사전 생성 재시도, 오래된 자료 정리 등). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
