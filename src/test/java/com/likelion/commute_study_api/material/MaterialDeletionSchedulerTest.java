package com.likelion.commute_study_api.material;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MaterialDeletionSchedulerTest {
    @Test void localDefaultDoesNotStartCleanupScheduler() {
        new ApplicationContextRunner().withUserConfiguration(MaterialDeletionScheduler.class)
                .withBean(MaterialDeletionCleanup.class, () -> mock(MaterialDeletionCleanup.class))
                .run(context -> assertThat(context).doesNotHaveBean(MaterialDeletionScheduler.class));
    }
    @Test void enabledSchedulerSubmitsCleanupOffThread() {
        var cleanup=mock(MaterialDeletionCleanup.class);
        var called=new CountDownLatch(1);
        doAnswer(inv -> { called.countDown(); return null; }).when(cleanup).cleanPending();
        new ApplicationContextRunner().withUserConfiguration(MaterialDeletionScheduler.class)
                .withBean(MaterialDeletionCleanup.class, () -> cleanup)
                .withPropertyValues("material.deletion-cleanup-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MaterialDeletionScheduler.class);
                    context.getBean(MaterialDeletionScheduler.class).schedule();
                    assertThat(called.await(5,TimeUnit.SECONDS)).isTrue();
                });
    }
}
