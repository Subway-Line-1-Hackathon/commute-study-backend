package com.likelion.commute_study_api.common.exception;

public record ErrorResponse(String code, String message, Object details) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), null);
	}

	public static ErrorResponse of(ErrorCode errorCode, String message, Object details) {
		return new ErrorResponse(errorCode.getCode(), message, details);
	}

	public static ErrorResponse of(ApiException e) {
		return new ErrorResponse(e.getErrorCode().getCode(), e.getMessage(), e.getDetails());
	}
}
