package com.likelion.commute_study_api.material;

import java.util.List;
import java.util.Locale;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class MaterialUploadValidator {
    public static final long MAX_BYTES = 30L * 1024 * 1024;
    public record ValidatedFile(MultipartFile file, String fileName, String type, String extension, String contentType) {}

    public ValidatedFile validate(List<MultipartFile> files, String kind) {
        if (files == null || files.size() != 1) throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
        return validateBatch(files, kind).getFirst();
    }

    public List<ValidatedFile> validateBatch(List<MultipartFile> files, String kind) {
        if (files == null || files.isEmpty() || files.size() > 20)
            throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "PNG는 최대 20장까지 업로드할 수 있습니다.");
        if (!"LECTURE".equals(kind)) throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "현재 kind는 LECTURE만 지원합니다.");
        // Validate every part before writing any Storage object.
        var validated = files.stream().map(this::validateFile).toList();
        if (validated.size() > 1 && validated.stream().anyMatch(file -> !"PNG".equals(file.type())))
            throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "여러 파일은 PNG만 함께 업로드할 수 있습니다.");
        return validated;
    }

    private ValidatedFile validateFile(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) throw new ApiException(MaterialErrorCode.FILE_TOO_LARGE);
        if (file.isEmpty()) throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "빈 파일은 업로드할 수 없습니다.");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) throw new ApiException(MaterialErrorCode.UNSUPPORTED_FILE_TYPE);
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255 || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(CommonErrorCode.VALIDATION_ERROR);
        String lower = name.toLowerCase(Locale.ROOT);
        String mime = file.getContentType();
        if (lower.endsWith(".pdf") && "application/pdf".equalsIgnoreCase(mime))
            return new ValidatedFile(file, name, "PDF", "pdf", "application/pdf");
        String pptx = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".pptx") && pptx.equalsIgnoreCase(mime))
            return new ValidatedFile(file, name, "PPT", "pptx", pptx);
        if (lower.endsWith(".png") && "image/png".equalsIgnoreCase(mime))
            return new ValidatedFile(file, name, "PNG", "png", "image/png");
        throw new ApiException(MaterialErrorCode.UNSUPPORTED_FILE_TYPE);
    }
}

