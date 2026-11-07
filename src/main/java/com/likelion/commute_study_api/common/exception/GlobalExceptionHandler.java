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

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
		var code = com.likelion.commute_study_api.material.MaterialErrorCode.FILE_TOO_LARGE;
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
	}

	@ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
			org.springframework.web.multipart.support.MissingServletRequestPartException.class,
			org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ErrorResponse> handleRequestBinding(Exception e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(CommonErrorCode.VALIDATION_ERROR));
	}

	@ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(CommonErrorCode.VALIDATION_ERROR));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("Unhandled exception", e);
		return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.getStatus())
				.body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
	}
}
