package com.likelion.commute_study_api.material;

import java.util.UUID;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialDeletionTransactions {
    private final MaterialDeletionRepository deletion;
    private final MaterialRepository materials;
    public MaterialDeletionTransactions(MaterialDeletionRepository deletion, MaterialRepository materials) {
        this.deletion = deletion; this.materials = materials;
    }

    /** Commit the tombstone and all cleanup intents together, without doing Storage HTTP in the transaction. */
    @Transactional
    public void delete(long id, UUID userId) {
        var target = deletion.lockOwned(id,userId).orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
        if (target.deleted()) return; // Repeated deletion by the owner is idempotent.
        if (!java.util.Set.of("READY", "FAILED", "EXPIRED").contains(target.status()))
            throw new ApiException(CommonErrorCode.STATE_CONFLICT);
        var material = materials.get(id);
        var refs = materials.deletionReferences(material);
        deletion.markDeleted(id);
        deletion.enqueue(id, refs);
    }
}
