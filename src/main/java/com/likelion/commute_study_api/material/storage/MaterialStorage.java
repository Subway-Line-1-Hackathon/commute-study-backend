package com.likelion.commute_study_api.material.storage;

import java.util.UUID;
import com.likelion.commute_study_api.material.MaterialUploadValidator.ValidatedFile;

public interface MaterialStorage {
    String reference(UUID userId, String extension);
    void upload(String reference, ValidatedFile file);
    String signedUrl(String reference, int ttlSeconds);
    void delete(String reference);
}

