package com.likelion.commute_study_api.ai;

import org.springframework.http.HttpStatus;

import com.likelion.commute_study_api.common.exception.ErrorCode;

public enum AiErrorCode implements ErrorCode {

	AI_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI 서버 호출에 실패했습니다."),
	AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 서버 응답이 지연되고 있습니다.");

	private final HttpStatus status;
	private final String message;

	AiErrorCode(HttpStatus status, String message) {
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
