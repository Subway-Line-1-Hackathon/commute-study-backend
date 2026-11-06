package com.likelion.commute_study_api.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException e) {
		if (e.getErrorCode().getStatus().is5xxServerError()) {
			log.error("ApiException: {}", e.getErrorCode().getCode(), e);
		}
		return ResponseEntity.status(e.getErrorCode().getStatus()).body(ErrorResponse.of(e));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		Map<String, String> details = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(
				fieldError -> details.put(fieldError.getField(), fieldError.getDefaultMessage())
		);
		return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.getStatus())
				.body(ErrorResponse.of(CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.VALIDATION_ERROR.getMessage(), details));
	}

	// 업로드 기능(material 도메인)이 생기면 FILE_TOO_LARGE 전용 코드로 옮긴다.
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(CommonErrorCode.VALIDATION_ERROR.getStatus())
				.body(ErrorResponse.of(CommonErrorCode.VALIDATION_ERROR, "파일 용량이 한도를 초과했습니다.", null));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.getStatus())
				.body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
	}
}
