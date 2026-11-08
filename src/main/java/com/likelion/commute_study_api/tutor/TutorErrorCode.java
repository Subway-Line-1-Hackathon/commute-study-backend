package com.likelion.commute_study_api.tutor;

import org.springframework.http.HttpStatus;
import com.likelion.commute_study_api.common.exception.ErrorCode;

public enum TutorErrorCode implements ErrorCode {
    AI_QUESTION_LIMIT;
    public HttpStatus getStatus() { return HttpStatus.TOO_MANY_REQUESTS; }
    public String getCode() { return name(); }
    public String getMessage() { return "오늘의 AI 질문 횟수를 모두 사용했습니다."; }
}
