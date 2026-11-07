package com.likelion.commute_study_api.material;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(prefix="material", name="cleanup-enabled", havingValue="true")
public class MaterialRetentionTriggers {
    private static final Logger log = LoggerFactory.getLogger(MaterialRetentionTriggers.class);
    private final MaterialRetentionService service;
    private final Executor executor;
    public MaterialRetentionTriggers(MaterialRetentionService service,
                                     @Qualifier("materialRetentionExecutor") Executor executor) {
        this.service = service; this.executor = executor;
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void uploaded(MaterialUploadedEvent event) { submit(() -> service.cleanUser(event.userId())); }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void settled(MaterialSettledEvent event) { submit(() -> service.cleanMaterialOwner(event.materialId())); }

    @Scheduled(cron="${material.cleanup-cron:0 0 3 * * *}", zone="Asia/Seoul")
    public void daily() { submit(() -> { service.recoverStalled(); service.cleanAll(); }); }

    // Recovery must not wait until tomorrow; the same opt-in gate protects the shared DB.
    @Scheduled(fixedDelayString="${material.recovery-check-interval:1m}")
    public void recover() { submit(service::recoverStalled); }

    private void submit(Runnable task) {
        try {
            executor.execute(() -> {
                try { task.run(); }
                catch (Exception e) { log.error("자료 정리 작업 실패"); }
            });
        } catch (RuntimeException e) {
            // A post-commit listener must not turn a committed upload into an HTTP failure.
            log.error("자료 정리 예약 실패; 다음 정기 실행에서 재시도");
        }
    }
}
