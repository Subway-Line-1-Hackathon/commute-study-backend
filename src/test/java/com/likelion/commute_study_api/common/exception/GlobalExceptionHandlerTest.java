package com.likelion.commute_study_api.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void ApiException은_errorCode의_상태와_코드로_응답한다() {
		ApiException e = new ApiException(CommonErrorCode.NOT_FOUND);

		ResponseEntity<ErrorResponse> response = handler.handleApiException(e);

		assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
		assertEquals("NOT_FOUND", response.getBody().code());
		assertEquals(CommonErrorCode.NOT_FOUND.getMessage(), response.getBody().message());
	}

	@Test
	void details가_있으면_응답에_그대로_담긴다() {
		ApiException e = new ApiException(CommonErrorCode.VALIDATION_ERROR, "닉네임 길이 오류", "nickname");

		ResponseEntity<ErrorResponse> response = handler.handleApiException(e);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("nickname", response.getBody().details());
	}

	@Test
	void 예상하지_못한_예외는_INTERNAL_ERROR로_변환되고_원래_메시지는_노출되지_않는다() {
		Exception e = new RuntimeException("db password is 1234");

		ResponseEntity<ErrorResponse> response = handler.handleUnexpected(e);

		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
		assertEquals("INTERNAL_ERROR", response.getBody().code());
		assertNotEquals("db password is 1234", response.getBody().message());
	}
}
