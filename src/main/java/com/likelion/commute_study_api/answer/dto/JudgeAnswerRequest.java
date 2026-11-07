package com.likelion.commute_study_api.answer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** POST /api/answers/judge 요청. 앱의 단답 입력 제한은 30자다(AI 서버 ↔ 백엔드 API 명세 7장). */
public record JudgeAnswerRequest(
		@NotNull @Positive Long questionId,
		@NotNull @Size(max = 30) String answer
) {
}
