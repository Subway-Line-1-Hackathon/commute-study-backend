package com.likelion.commute_study_api.material;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialRetentionTransactions {
    private final MaterialRetentionRepository retention;
    private final MaterialRepository materials;
    private final ApplicationEventPublisher events;
    public MaterialRetentionTransactions(MaterialRetentionRepository retention, MaterialRepository materials,
                                         ApplicationEventPublisher events) {
        this.retention = retention; this.materials = materials; this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> reserve(long id, UUID userId) {
        if (!retention.reserve(id, userId)) return List.of();
        return materials.fileReferences(materials.get(id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(long id, UUID userId) { return retention.finish(id, userId); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recover(Instant cutoff) {
        int count = 0;
        for (long id : retention.staleProcessing(cutoff)) {
            if (retention.recover(id, cutoff)) {
                count++;
                events.publishEvent(new MaterialSettledEvent(id));
            }
        }
        return count;
    }
}
