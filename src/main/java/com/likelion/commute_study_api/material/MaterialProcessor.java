package com.likelion.commute_study_api.material;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.likelion.commute_study_api.ai.AiCallException;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.material.*;
import com.likelion.commute_study_api.ai.support.AiAsyncConfig;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.config.MaterialProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MaterialProcessor {
    private static final Logger log = LoggerFactory.getLogger(MaterialProcessor.class);
    private final MaterialRepository repository;
    private final MaterialTransactions transactions;
    private final MaterialStorage storage;
    private final MaterialAiClient ai;
    private final AiProperties aiProperties;
    private final MaterialProperties properties;
    private final Executor executor;
    private final ConcurrentHashMap<java.util.UUID, Object> inFlight = new ConcurrentHashMap<>();

    public MaterialProcessor(MaterialRepository repository, MaterialTransactions transactions, MaterialStorage storage,
                             MaterialAiClient ai, AiProperties aiProperties, MaterialProperties properties,
                             @Qualifier(AiAsyncConfig.AI_EXECUTOR) Executor executor) {
        this.repository = repository; this.transactions = transactions; this.storage = storage;
        this.ai = ai; this.aiProperties = aiProperties; this.properties = properties; this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void queued(MaterialQueuedEvent event) {
        long id = event.materialId();
        Object attempt = new Object();
        if (inFlight.putIfAbsent(event.token(), attempt) != null) return;
        var stopped = new AtomicBoolean();
        try {
            CompletableFuture.supplyAsync(() -> {
                if (stopped.get()) return null;
                if (!event.retry() && !transactions.claimUpload(id, event.token())) return null;
                var material = repository.get(id);
                return "PROCESSING".equals(material.status()) && event.token().equals(repository.processingToken(id)) ? material : null;
            }, executor)
                    // A completed claim must not run this continuation with AFTER_COMMIT's bound connection.
                    .thenComposeAsync(material -> material == null || stopped.get()
                    ? CompletableFuture.<MaterialProcessResponse>completedFuture(null) : call(material, 0, stopped), executor)
                    .orTimeout(properties.getProcessingTimeoutMinutes(), TimeUnit.MINUTES)
                    .whenComplete((response, failure) -> {
                        stopped.set(true);
                        try {
                            if (failure != null) {
                                inFlight.remove(event.token(), attempt);
                                markFailed(id, event.token(), failure);
                            }
                            else if (response != null) {
                                try { transactions.complete(id, event.token(), response); }
                                catch (Exception e) { inFlight.remove(event.token(), attempt); markFailed(id, event.token(), e); }
                            }
                        } finally { inFlight.remove(event.token(), attempt); }
                    });
        } catch (Exception e) {
            stopped.set(true);
            inFlight.remove(event.token(), attempt);
            markFailed(id, event.token(), e);
        }
    }

    private CompletableFuture<MaterialProcessResponse> call(MaterialRepository.Material material, int retries, AtomicBoolean stopped) {
        if (stopped.get()) return CompletableFuture.failedFuture(new TimeoutException());
        try {
            var urls = repository.fileReferences(material).stream()
                    .map(reference -> storage.signedUrl(reference, Math.multiplyExact(aiProperties.getSignedUrlTtlMinutes(), 60)))
                    .toList();
            if (stopped.get()) return CompletableFuture.failedFuture(new TimeoutException());
            var request = new MaterialProcessRequest(material.id(), material.type(), material.kind(), material.name(), urls);
            return ai.process(request).handle((response, failure) -> {
                if (failure == null) return response == null
                        ? CompletableFuture.<MaterialProcessResponse>failedFuture(new IllegalArgumentException("Empty AI response"))
                        : CompletableFuture.completedFuture(response);
                Throwable cause = unwrap(failure);
                if (cause instanceof AiCallException ex && "FILE_FETCH_FAILED".equals(ex.getAiCode()) && retries == 0 && !stopped.get())
                    return call(material, 1, stopped);
                return CompletableFuture.<MaterialProcessResponse>failedFuture(cause);
            }).thenCompose(future -> future);
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    private void markFailed(long id, java.util.UUID token, Throwable failure) {
        try { transactions.failed(id, token, safeMessage(unwrap(failure))); }
        catch (Exception ignored) {
            // DB outage cannot be repaired here. Never log raw SQL arguments, signed URLs, or AI content.
            log.error("자료 실패 상태 저장 불가 materialId={}", id);
        }
    }

    static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure;
    }

    static String safeMessage(Throwable failure) {
        if (failure instanceof AiCallException ex) return switch (ex.getAiCode()) {
            case "UNSUPPORTED_FILE" -> "지원하지 않는 파일입니다.";
            case "PAGE_LIMIT_EXCEEDED" -> "자료는 100쪽 이하여야 합니다.";
            case "TEXT_EXTRACTION_FAILED" -> "텍스트를 추출할 수 없습니다.";
            case "FILE_FETCH_FAILED" -> "저장된 파일을 가져올 수 없습니다. 다시 시도해주세요.";
            default -> "자료 처리에 실패했습니다.";
        };
        if (failure instanceof TimeoutException || failure instanceof ApiException ex && ex.getErrorCode() == AiErrorCode.AI_TIMEOUT)
            return "AI 처리 시간이 초과되었습니다. 다시 시도해주세요.";
        if (failure instanceof ApiException ex && ex.getErrorCode() == MaterialErrorCode.STORAGE_UNAVAILABLE)
            return "저장된 파일에 접근할 수 없습니다. 다시 시도해주세요.";
        return "자료 처리에 실패했습니다. 다시 시도해주세요.";
    }
}

