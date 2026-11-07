package com.likelion.commute_study_api.material;

import com.likelion.commute_study_api.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

public enum MaterialErrorCode implements ErrorCode {
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일은 30MB 이하여야 합니다."),
    UNSUPPORTED_FILE_TYPE(HttpStatus.BAD_REQUEST, "PDF, PPTX 또는 PNG 파일만 지원합니다."),
    STORAGE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "자료 저장소에 접근할 수 없습니다.");
    private final HttpStatus status;
    private final String message;
    MaterialErrorCode(HttpStatus status, String message) { this.status = status; this.message = message; }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return name(); }
    public String getMessage() { return message; }
}

