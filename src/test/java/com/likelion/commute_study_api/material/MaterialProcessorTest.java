package com.likelion.commute_study_api.material;

import java.util.concurrent.*;
import com.likelion.commute_study_api.ai.material.*;
import com.likelion.commute_study_api.common.config.*;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MaterialProcessorTest {
    final MaterialRepository repository=mock(MaterialRepository.class);
    final MaterialTransactions transactions=mock(MaterialTransactions.class);
    final MaterialStorage storage=mock(MaterialStorage.class);
    final MaterialAiClient ai=mock(MaterialAiClient.class);

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
