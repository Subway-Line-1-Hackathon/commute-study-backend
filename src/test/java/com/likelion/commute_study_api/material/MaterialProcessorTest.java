package com.likelion.commute_study_api.material;

import java.util.concurrent.*;
import com.likelion.commute_study_api.ai.material.*;
import com.likelion.commute_study_api.common.config.*;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.likelion.commute_study_api.testutil.CompletingWorkerExecutor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MaterialProcessorTest {
    final MaterialRepository repository=mock(MaterialRepository.class);
    final MaterialTransactions transactions=mock(MaterialTransactions.class);
    final MaterialStorage storage=mock(MaterialStorage.class);
    final MaterialAiClient ai=mock(MaterialAiClient.class);

    @Test
    void alreadyCompletedClaimStillCallsAiOutsideAfterCommitResources() throws Exception {
        var event = new MaterialQueuedEvent(1, false);
        when(transactions.claimUpload(1, event.token())).thenReturn(true);
        when(repository.processingToken(1)).thenReturn(event.token());
        when(repository.get(1)).thenReturn(new MaterialRepository.Material(1,2,"ref","a.pdf","PDF","LECTURE","PROCESSING",null));
        when(repository.fileReferences(any())).thenReturn(java.util.List.of("ref"));
        when(storage.signedUrl(anyString(),anyInt())).thenReturn("https://signed");
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:material-after-commit-" + java.util.UUID.randomUUID(), "sa", "");
        var transactionSeen = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var connectionSeen = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var threadSeen = new java.util.concurrent.atomic.AtomicReference<String>();
        var response = new CompletableFuture<MaterialProcessResponse>();
        when(ai.process(any())).thenAnswer(invocation -> {
            transactionSeen.set(TransactionSynchronizationManager.isActualTransactionActive());
            connectionSeen.set(TransactionSynchronizationManager.hasResource(dataSource));
            threadSeen.set(Thread.currentThread().getName());
            return response;
        });

        try (var executor = new CompletingWorkerExecutor("material-boundary-worker")) {
            var processor = new MaterialProcessor(repository,transactions,storage,ai,new AiProperties(),new MaterialProperties(),executor);
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx ->
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() {
                            // Spring still binds the committed transaction while invoking AFTER_COMMIT listeners.
                            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                            assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isTrue();
                            processor.queued(event);
                        }
                    }));
            assertThat(threadSeen.get()).isEqualTo("material-boundary-worker");
            assertThat(transactionSeen.get()).isFalse();
            assertThat(connectionSeen.get()).isFalse();
            var result = MaterialPipelineTest.result("완료",1,2);
            response.complete(result);
            verify(transactions).complete(1,event.token(),result);
            verify(transactions,never()).failed(anyLong(),any(),anyString());
        }
    }

    @Test
    void recoveredAttemptDoesNotBlockNewRetryAndDuplicateEventIsIgnored() {
        var old=new MaterialQueuedEvent(1,false);
        var retry=new MaterialQueuedEvent(1,true);
        when(transactions.claimUpload(1,old.token())).thenReturn(true);
        when(repository.get(1)).thenReturn(new MaterialRepository.Material(1,2,"ref","a.pdf","PDF","LECTURE","PROCESSING",null));
        when(repository.fileReferences(any())).thenReturn(java.util.List.of("ref"));
        when(storage.signedUrl(anyString(),anyInt())).thenReturn("https://signed");
        var oldResponse=new CompletableFuture<MaterialProcessResponse>();
        var newResponse=new CompletableFuture<MaterialProcessResponse>();
        when(ai.process(any())).thenReturn(oldResponse,newResponse);
        var processor=new MaterialProcessor(repository,transactions,storage,ai,new AiProperties(),new MaterialProperties(),Runnable::run);
        when(repository.processingToken(1)).thenReturn(old.token());
        processor.queued(old); processor.queued(old);
        verify(ai,times(1)).process(any());
        when(repository.processingToken(1)).thenReturn(retry.token());
        processor.queued(retry); processor.queued(retry);
        verify(ai,times(2)).process(any());
        var result=MaterialPipelineTest.result("new",1,2);
        newResponse.complete(result);
        verify(transactions).complete(1,retry.token(),result);
        oldResponse.completeExceptionally(new IllegalStateException());
        verify(transactions).failed(eq(1L),eq(old.token()),anyString()); // DB token condition rejects this old callback.
    }

    @Test
    void rejectedExecutorMarksFailedInsteadOfLeavingUploaded() {
        var processor=new MaterialProcessor(repository,transactions,storage,ai,new AiProperties(),new MaterialProperties(),
                task -> { throw new RejectedExecutionException("saturated"); });
        processor.queued(new MaterialQueuedEvent(1,false));
        verify(transactions).failed(eq(1L),any(),anyString());
        verifyNoInteractions(ai);
    }

    @Test
    void processingDeadlineFailsAndLateAiResponseCannotCommit() {
        var properties=new MaterialProperties(); properties.setProcessingTimeoutMinutes(0);
        var event = new MaterialQueuedEvent(1,false);
        when(transactions.claimUpload(1,event.token())).thenReturn(true);
        when(repository.processingToken(1)).thenReturn(event.token());
        when(repository.get(1)).thenReturn(new MaterialRepository.Material(1,2,"ref","a.pdf","PDF","LECTURE","PROCESSING",null));
        when(repository.fileReferences(any())).thenReturn(java.util.List.of("ref"));
        when(storage.signedUrl(anyString(),anyInt())).thenReturn("https://signed");
        var response=new CompletableFuture<MaterialProcessResponse>(); when(ai.process(any())).thenReturn(response);
        var processor=new MaterialProcessor(repository,transactions,storage,ai,new AiProperties(),properties,Runnable::run);
        processor.queued(event);
        verify(transactions,timeout(3000)).failed(eq(1L),eq(event.token()),anyString());
        response.complete(MaterialPipelineTest.result("late",1,2));
        verify(transactions,never()).complete(anyLong(),any(),any());
    }
}
