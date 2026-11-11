package com.likelion.commute_study_api.user;

import org.springframework.http.HttpStatus;

import com.likelion.commute_study_api.common.exception.ErrorCode;

public enum UserErrorCode implements ErrorCode {

	ACCOUNT_DELETE_FAILED(HttpStatus.BAD_GATEWAY, "회원 탈퇴를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;

	UserErrorCode(HttpStatus status, String message) {
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
