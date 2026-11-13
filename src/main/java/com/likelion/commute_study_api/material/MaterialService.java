package com.likelion.commute_study_api.material;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.material.storage.MaterialStorage;
import com.likelion.commute_study_api.subject.SubjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MaterialService {
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private final CurrentUser currentUser;
    private final SubjectRepository subjects;
    private final MaterialUploadValidator validator;
    private final MaterialStorage storage;
    private final MaterialTransactions transactions;
    private final MaterialRepository repository;
    private final MaterialDeletionTransactions deletion;
    public MaterialService(CurrentUser currentUser, SubjectRepository subjects, MaterialUploadValidator validator,
                           MaterialStorage storage, MaterialTransactions transactions, MaterialRepository repository,
                           MaterialDeletionTransactions deletion) {
        this.currentUser = currentUser; this.subjects = subjects; this.validator = validator;
        this.storage = storage; this.transactions = transactions; this.repository = repository;
        this.deletion = deletion;
    }

    // Storage and AI calls must not run in a DB transaction.
    public Uploaded upload(Jwt jwt, long subjectId, List<MultipartFile> files, String kind) {
        var validated = validator.validateBatch(files, kind);
        var userId = currentUser.ensureUser(jwt);
        subjects.requireActiveOwned(subjectId, userId);
        var references = new java.util.ArrayList<String>();
        try {
            for (var file : validated) {
                String reference = storage.reference(userId, file.extension());
                references.add(reference); // Includes an upload whose response is lost after Storage accepted it.
                storage.upload(reference, file);
            }
            long id = transactions.uploaded(userId, subjectId, references, validated, kind);
            return new Uploaded(id, "UPLOADED");
        } catch (Exception e) {
            for (String reference : references) {
                try { storage.delete(reference); }
                catch (Exception ignored) {
                    try { deletion.enqueueUnattached(reference); }
                    catch (Exception queueFailure) {
                        e.addSuppressed(queueFailure);
                        log.error("업로드 보상 삭제 예약 실패 userId={}", userId);
                    }
                }
            }
            throw e;
        }
    }

    public MaterialList list(Jwt jwt) {
        return new MaterialList(repository.listByUser(currentUser.authenticatedId(jwt)));
    }

    public Status status(Jwt jwt, long id) {
        var material = repository.owned(id, currentUser.ensureUser(jwt));
        return new Status(material.status(), repository.categoryCount(id), "FAILED".equals(material.status()) ? material.error() : null);
    }

    public Uploaded retry(Jwt jwt, long id) {
        transactions.retry(id, currentUser.ensureUser(jwt));
        return new Uploaded(id, "PROCESSING");
    }

    public record Uploaded(long materialId, String status) {}
    public record Status(String status, int categoryCount, @JsonInclude(JsonInclude.Include.NON_NULL) String error) {}
    public record MaterialList(List<MaterialRepository.MaterialSummary> materials) {}
}

