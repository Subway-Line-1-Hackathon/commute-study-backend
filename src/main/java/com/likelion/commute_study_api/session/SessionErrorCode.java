package com.likelion.commute_study_api.session;

import org.springframework.http.HttpStatus;

import com.likelion.commute_study_api.common.exception.ErrorCode;

/** 세션(커리큘럼) 도메인 에러 코드. 백엔드 개발 명세서 10장. */
public enum SessionErrorCode implements ErrorCode {

	SETUP_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "닉네임·통학 시간 설정과 과목 등록이 필요합니다."),
	CATEGORY_NOT_READY(HttpStatus.UNPROCESSABLE_CONTENT, "문제를 준비 중인 단원이 포함되어 있습니다."),
	// 명세에 코드가 없어 추가(#27 PR에서 확인). AI 실패가 원인이면 AI_UNAVAILABLE/AI_TIMEOUT을 대신 쓴다.
	NO_QUESTIONS_AVAILABLE(HttpStatus.UNPROCESSABLE_CONTENT, "조건에 맞는 문제가 없어 세션을 만들 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	SessionErrorCode(HttpStatus status, String message) {
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
