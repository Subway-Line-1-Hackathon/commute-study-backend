package com.likelion.commute_study_api.ai;

import lombok.Getter;

/**
 * 호출한 쪽이 AI 서버의 원래 에러 코드로 분기해야 하는 경우(자료 FAILED 사유 등)에 던진다.
 * UNSUPPORTED_FILE / PAGE_LIMIT_EXCEEDED / TEXT_EXTRACTION_FAILED / FILE_FETCH_FAILED 전용.
 */
@Getter
public class AiCallException extends RuntimeException {

	private final String aiCode;

	public AiCallException(String aiCode, String message) {
		super(message);
		this.aiCode = aiCode;
	}
}
