package com.likelion.commute_study_api.subject;

import java.util.UUID;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.material.MaterialDeletionTransactions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectDeletionService {
    private final SubjectDeletionRepository subjects;
    private final MaterialDeletionTransactions materials;

    public SubjectDeletionService(SubjectDeletionRepository subjects, MaterialDeletionTransactions materials) {
        this.subjects = subjects;
        this.materials = materials;
    }

    @Transactional
    public void delete(long id, UUID userId) {
        boolean deleted = subjects.lockOwned(id, userId)
                .orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
        if (deleted) return;
        // Uploads/workers lock the subject first; a material conflict rolls back every deletion and queue entry.
        for (long materialId : subjects.lockMaterials(id)) materials.delete(materialId, userId);
        subjects.markDeleted(id);
    }
}
