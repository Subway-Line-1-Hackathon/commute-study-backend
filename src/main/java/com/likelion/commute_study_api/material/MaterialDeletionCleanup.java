package com.likelion.commute_study_api.material;

import com.likelion.commute_study_api.material.storage.MaterialStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Storage calls run without a DB transaction; each successful object is acknowledged separately. */
@Service
public class MaterialDeletionCleanup {
    private static final Logger log = LoggerFactory.getLogger(MaterialDeletionCleanup.class);
    private final MaterialDeletionRepository repository;
    private final MaterialStorage storage;
    public MaterialDeletionCleanup(MaterialDeletionRepository repository, MaterialStorage storage) {
        this.repository=repository; this.storage=storage;
    }
    public void cleanPending() {
        for (var object : repository.pending(100)) {
            try {
                storage.delete(object.reference());
                repository.completed(object.id());
            } catch (Exception e) {
                // Keep the reference after failures and retry later, including lost Storage responses.
                repository.failed(object.id());
                log.warn("삭제된 자료의 원본 정리 실패 queueId={}", object.id());
            }
        }
    }
}
