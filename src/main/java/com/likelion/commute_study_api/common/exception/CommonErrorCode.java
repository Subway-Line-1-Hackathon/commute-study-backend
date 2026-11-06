package com.likelion.commute_study_api.common.exception;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력 형식 또는 범위가 올바르지 않습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	STATE_CONFLICT(HttpStatus.CONFLICT, "허용되지 않는 상태 전이입니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

	private final HttpStatus status;
	private final String message;

	CommonErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	@Override
	public HttpStatus getStatus() {
		return status;
	}

	@Override
	public String getCode() {
		return name();
	}

	@Override
	public String getMessage() {
		return message;
	}
}
