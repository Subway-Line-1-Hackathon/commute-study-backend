package com.likelion.commute_study_api.ai.support;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.likelion.commute_study_api.common.config.AiProperties;

/**
 * AI-01·AI-02(긴 호출)을 동시 ai.max-concurrency개까지만 실행하는 전용 스레드풀(명세서 8장, 합의 #2).
 * 다른 짧은 호출(AI-03/05/06)은 이 풀을 쓰지 않는다 — 긴 호출이 전부 몰리면 짧은 호출까지 밀리기 때문.
 */
@Configuration
@EnableAsync
public class AiAsyncConfig {

	public static final String AI_EXECUTOR = "aiExecutor";

	@Bean(AI_EXECUTOR)
	public Executor aiExecutor(AiProperties aiProperties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(aiProperties.getMaxConcurrency());
		executor.setMaxPoolSize(aiProperties.getMaxConcurrency());
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("ai-exec-");
		// 개발용 X-Mock-Error를 업로드 요청 → 단원화·사전 생성 작업으로 넘긴다(설정이 꺼져 있으면 AI 서버로는 안 보냄)
		executor.setTaskDecorator(MockErrorContext.taskDecorator());
		executor.initialize();
		return executor;
	}
}
