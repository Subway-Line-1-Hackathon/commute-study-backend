package com.likelion.commute_study_api.session.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * POST /api/attempts 요청. 앱이 기기에 쌓아 둔 응답을 한 번에 보낸다(오프라인 일괄 전송, 명세서 9.5·9.6).
 * 같은 (sessionId, questionId)를 다시 보내면 서버가 건너뛴다.
 */
public record SaveAttemptsRequest(
		@NotNull @Positive Long sessionId,
		@NotEmpty @Size(max = 100) List<@Valid @NotNull AttemptItem> attempts
) {

	/**
	 * @param answer     사용자가 고른·입력한 답. 세션 응답의 answer와 같은 형식: O/X는 "O"/"X", 객관식은 선택지 번호 "1"~"4", 단답은 입력한 글자
	 * @param correct    앱이 판단한 정오. O/X·객관식은 서버가 다시 채점해 덮어쓴다. 단답은 판정 API 결과나 자가 판정(null이면 채점 대기)
	 * @param selfJudged 단답을 사용자가 맞음·틀림으로 직접 고른 경우 true
	 * @param answeredAt 기기에서 응답한 시각(ISO-8601)
	 */
	public record AttemptItem(
			@NotNull @Positive Long questionId,
			@NotNull @Size(max = 500) String answer,
			Boolean correct,
			Boolean selfJudged,
			@NotNull Instant answeredAt
	) {
		public boolean selfJudgedOrFalse() {
			return Boolean.TRUE.equals(selfJudged);
		}
	}
}
