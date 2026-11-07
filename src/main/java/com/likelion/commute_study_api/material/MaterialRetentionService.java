package com.likelion.commute_study_api.material;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.UUID;
import com.likelion.commute_study_api.common.config.MaterialProperties;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MaterialRetentionService {
    private static final Logger log = LoggerFactory.getLogger(MaterialRetentionService.class);
    private final MaterialRetentionRepository repository;
    private final MaterialRetentionTransactions transactions;
    private final MaterialStorage storage;
    private final MaterialProperties properties;
    private final Clock clock;

    public MaterialRetentionService(MaterialRetentionRepository repository, MaterialRetentionTransactions transactions,
                                    MaterialStorage storage, MaterialProperties properties,
                                    @Qualifier("materialRetentionClock") Clock clock) {
        this.repository = repository; this.transactions = transactions; this.storage = storage;
        this.properties = properties; this.clock = clock;
    }

    /** Single-instance serialization. No DB transaction is held over Storage HTTP requests. */
    public synchronized Result cleanUser(UUID userId) {
        int expired = 0, failed = 0;
        var attempted = new HashSet<Long>();
        var now = clock.instant();
        for (var item : repository.candidates(userId)) {
            var expires = item.expiresAt() != null ? item.expiresAt()
                    : item.createdAt().plus(properties.getRetentionDays(), ChronoUnit.DAYS);
            if (item.pending() || !expires.isAfter(now)) {
                attempted.add(item.id());
                int outcome = expire(item.id(), userId);
                if (outcome > 0) expired++; else if (outcome < 0) failed++;
            }
        }
        // Recount after age expiration so we never expire extra files unnecessarily.
        var remaining = repository.candidates(userId);
        int excess = Math.max(0, remaining.size() - properties.getMaxPerUser());
        for (int i = 0; i < excess; i++) {
            long id = remaining.get(i).id();
            if (!attempted.add(id)) continue; // Failed oldest stays pending; don't delete newer replacements.
            int outcome = expire(id, userId);
            if (outcome > 0) expired++; else if (outcome < 0) failed++;
        }
        log.info("자료 정리 userId={} expired={} failed={}", userId, expired, failed);
        return new Result(expired, failed);
    }

    public Result cleanAll() {
        int expired = 0, failed = 0;
        for (UUID userId : repository.usersWithRetainedMaterials()) {
            try {
                var result = cleanUser(userId);
                expired += result.expired(); failed += result.failed();
            } catch (Exception e) { failed++; log.error("자료 정리 실패 userId={}", userId); }
        }
        log.info("자료 정리 전체 expired={} failed={}", expired, failed);
        return new Result(expired, failed);
    }

    public void cleanMaterialOwner(long materialId) { repository.owner(materialId).ifPresent(this::cleanUser); }

    public int recoverStalled() {
        int recovered = transactions.recover(clock.instant().minus(properties.getProcessingTimeoutMinutes(), ChronoUnit.MINUTES));
        log.info("자료 고착 복구 recovered={}", recovered);
        return recovered;
    }

    private int expire(long id, UUID userId) {
        try {
            var references = transactions.reserve(id, userId);
            if (references.isEmpty()) return 0; // Retry won the race, or already expired.
            for (String reference : references) storage.delete(reference);
            return transactions.finish(id, userId) ? 1 : 0;
        } catch (Exception e) {
            // Leave READY/FAILED and references intact. Pending intent remains for the next run.
            log.error("자료 원본 정리 실패 materialId={}", id);
            return -1;
        }
    }

    public record Result(int expired, int failed) {}
}
