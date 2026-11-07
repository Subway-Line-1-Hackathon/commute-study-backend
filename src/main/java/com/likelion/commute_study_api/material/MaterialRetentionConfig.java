package com.likelion.commute_study_api.material;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class MaterialRetentionConfig {
    @Bean
    public Clock materialRetentionClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(prefix="material", name="cleanup-enabled", havingValue="true")
    public ThreadPoolTaskExecutor materialRetentionExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("material-retention-");
        return executor;
    }
}
