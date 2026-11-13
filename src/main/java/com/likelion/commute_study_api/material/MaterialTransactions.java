package com.likelion.commute_study_api.material;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.likelion.commute_study_api.ai.material.MaterialProcessResponse;
import com.likelion.commute_study_api.common.config.MaterialProperties;
import com.likelion.commute_study_api.common.event.MaterialProcessedEvent;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.subject.SubjectRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialTransactions {
    private final MaterialRepository repository;
    private final MaterialResultValidator validator;
    private final ApplicationEventPublisher events;
    private final MaterialProperties properties;
    private final SubjectRepository subjects;
    public MaterialTransactions(MaterialRepository repository, MaterialResultValidator validator,
                                ApplicationEventPublisher events, MaterialProperties properties, SubjectRepository subjects) {
        this.repository = repository; this.validator = validator; this.events = events; this.properties = properties;
        this.subjects = subjects;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long uploaded(UUID userId, long subjectId, java.util.List<String> references,
                         java.util.List<MaterialUploadValidator.ValidatedFile> files, String kind) {
        subjects.findOwnedForUpdate(subjectId, userId)
                .orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND));
        var first = files.getFirst();
        long size = files.stream().mapToLong(file -> file.file().getSize()).sum();
        String type = MaterialUploadValidator.batchType(files);
        long id = repository.insert(subjectId, references.getFirst(), first.fileName(), type, kind, size,
                Instant.now().plus(properties.getRetentionDays(), ChronoUnit.DAYS));
        if (MaterialUploadValidator.isImage(type)) repository.insertFiles(id, references);
        events.publishEvent(new MaterialUploadedEvent(id, userId));
        events.publishEvent(new MaterialQueuedEvent(id, false));
        return id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(long id, UUID userId) {
        var material = repository.owned(id, userId);
        if (!subjects.lockActive(material.subjectId())) throw new ApiException(CommonErrorCode.NOT_FOUND);
        var event = new MaterialQueuedEvent(id, true);
        if (!repository.claim(id, "FAILED", event.token())) throw new ApiException(CommonErrorCode.STATE_CONFLICT);
        events.publishEvent(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimUpload(long id, UUID token) {
        return subjects.lockActive(repository.get(id).subjectId()) && repository.claim(id, "UPLOADED", token);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long id, UUID token, String message) {
        if (!subjects.lockActive(repository.get(id).subjectId())) return;
        if (repository.fail(id, token, message)) events.publishEvent(new MaterialSettledEvent(id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long id, UUID token, MaterialProcessResponse result) {
        if (!subjects.lockActive(repository.get(id).subjectId())) return;
        validator.validate(result);
        if (repository.saveResult(id, token, result)) {
            events.publishEvent(new MaterialProcessedEvent(id, repository.get(id).subjectId()));
            events.publishEvent(new MaterialSettledEvent(id));
        }
    }
}

