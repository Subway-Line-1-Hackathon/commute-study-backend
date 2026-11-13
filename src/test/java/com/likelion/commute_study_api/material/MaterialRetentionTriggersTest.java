package com.likelion.commute_study_api.material;

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaterialRetentionTriggersTest {
    final ApplicationContextRunner context=new ApplicationContextRunner()
            .withUserConfiguration(Config.class,MaterialRetentionConfig.class,MaterialRetentionTriggers.class);

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Config {
        @Bean MaterialRetentionService service() { return mock(MaterialRetentionService.class); }
        @Bean(destroyMethod="destroy") SingleConnectionDataSource dataSource() {
            return new SingleConnectionDataSource("jdbc:h2:mem:triggers-"+UUID.randomUUID(),"sa","",true);
        }
        @Bean PlatformTransactionManager transactionManager(SingleConnectionDataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
    }

    @Test void disabledByDefaultAndWhenExplicitlyFalse() {
        context.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(MaterialRetentionTriggers.class);
            assertThat(ctx).doesNotHaveBean("materialRetentionExecutor");
        });
        context.withPropertyValues("material.cleanup-enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(MaterialRetentionTriggers.class));
    }

    @Test void enabledHooksRunOnlyAfterCommitAndNotAfterRollback() {
        context.withPropertyValues("material.cleanup-enabled=true").run(ctx -> {
            var service=ctx.getBean(MaterialRetentionService.class);
            var executor=ctx.getBean("materialRetentionExecutor",ThreadPoolTaskExecutor.class);
            var tx=new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class));
            UUID user=UUID.randomUUID();
            tx.executeWithoutResult(status -> {
                ctx.publishEvent(new MaterialUploadedEvent(1,user));
                ctx.publishEvent(new MaterialSettledEvent(1));
                verifyNoInteractions(service);
                assertThat(executor.getThreadPoolExecutor().getTaskCount()).isZero();
            });
            // timeout verification holds synchronized cleanUser's monitor and can block the worker.
            // A barrier on the real single-worker executor waits outside that monitor, within the same 2s budget.
            executor.submit(() -> {}).get(2,TimeUnit.SECONDS);
            verify(service).cleanUser(user);
            verify(service).cleanMaterialOwner(1);
            tx.executeWithoutResult(status -> {
                ctx.publishEvent(new MaterialSettledEvent(2)); status.setRollbackOnly();
            });
            ctx.publishEvent(new MaterialSettledEvent(3)); // No transaction: no cleanup.
            executor.submit(() -> {}).get(2,TimeUnit.SECONDS);
            verify(service,never()).cleanMaterialOwner(2);
            verify(service,never()).cleanMaterialOwner(3);
            verifyNoMoreInteractions(service);
        });
    }

    @Test void dailyUsesKstAndRunsRecoveryBeforeExpiry() throws Exception {
        var service=mock(MaterialRetentionService.class);
        var triggers=new MaterialRetentionTriggers(service,Runnable::run);
        triggers.daily();
        var order=inOrder(service); order.verify(service).recoverStalled(); order.verify(service).cleanAll();
        triggers.recover(); verify(service,times(2)).recoverStalled();
        var schedule=MaterialRetentionTriggers.class.getMethod("daily").getAnnotation(Scheduled.class);
        assertThat(schedule.zone()).isEqualTo("Asia/Seoul");
        assertThat(schedule.cron()).isEqualTo("${material.cleanup-cron:0 0 3 * * *}");
    }

    @Test void queueRejectionAndTaskFailureNeverEscapeCommittedUploadListener() {
        var service=mock(MaterialRetentionService.class);
        var rejected=new MaterialRetentionTriggers(service,task -> { throw new RejectedExecutionException(); });
        assertThatCode(() -> rejected.uploaded(new MaterialUploadedEvent(1,UUID.randomUUID()))).doesNotThrowAnyException();
        doThrow(new IllegalStateException()).when(service).cleanMaterialOwner(1);
        assertThatCode(() -> new MaterialRetentionTriggers(service,Runnable::run).settled(new MaterialSettledEvent(1)))
                .doesNotThrowAnyException();
    }
}
