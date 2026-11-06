package com.likelion.commute_study_api.common.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

	HttpStatus getStatus();

	String getCode();

	String getMessage();
}
