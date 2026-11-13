package com.likelion.commute_study_api.material;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Independent of natural-expiration cleanup; queued references survive process restarts. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix="material", name="deletion-cleanup-enabled", havingValue="true")
public class MaterialDeletionScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaterialDeletionScheduler.class);
    private final MaterialDeletionCleanup cleanup;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ThreadPoolTaskExecutor executor;
    public MaterialDeletionScheduler(MaterialDeletionCleanup cleanup,
            @org.springframework.beans.factory.annotation.Qualifier("materialDeletionExecutor") ThreadPoolTaskExecutor executor) {
        this.cleanup=cleanup; this.executor=executor;
    }
    @Bean(name="materialDeletionExecutor")
    public static ThreadPoolTaskExecutor deletionExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("material-delete-");
        return executor;
    }
    @Scheduled(initialDelayString="${material.deletion-cleanup-interval:1m}",
               fixedDelayString="${material.deletion-cleanup-interval:1m}")
    public void schedule() {
        if (!running.compareAndSet(false,true)) return;
        try {
            executor.execute(() -> {
                try { cleanup.cleanPending(); }
                catch (Exception e) { log.error("삭제 원본 재시도 실패"); }
                finally { running.set(false); }
            });
        } catch (RuntimeException e) {
            running.set(false);
            log.error("삭제 원본 재시도 예약 실패");
        }
    }
}
