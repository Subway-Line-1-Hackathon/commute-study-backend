package com.likelion.commute_study_api.feedback;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.likelion.commute_study_api.ai.support.MockErrorContext;

/**
 * 통학 후 피드백(AI-06) 전용 스레드풀.
 * aiExecutor(AI-01·02 긴 호출 전용)와 나눈다 — 문제 생성이 몰려 있을 때 피드백이 그 뒤에 밀리지 않게.
 */
@Configuration
@EnableAsync
public class FeedbackAsyncConfig {

	public static final String FEEDBACK_EXECUTOR = "feedbackExecutor";

	@Bean(FEEDBACK_EXECUTOR)
	public Executor feedbackExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("feedback-");
		// 개발용 X-Mock-Error를 세션 종료 요청 → 피드백 작업으로 넘긴다(설정이 꺼져 있으면 AI 서버로는 안 보냄)
		executor.setTaskDecorator(MockErrorContext.taskDecorator());
		// 제출 실패는 리스너가 잡아 FAILED로 기록한다. 조용히 버리면 PENDING이 영구히 남는다.
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		// 배포로 종료될 때 진행 중인 호출(최대 15초)은 마치고 내려간다
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(20);
		executor.initialize();
		return executor;
	}
}
