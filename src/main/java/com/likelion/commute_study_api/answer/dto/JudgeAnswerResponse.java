package com.likelion.commute_study_api.answer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * POST /api/answers/judge 응답(명세서 7장, 9.7).
 * <ul>
 * <li>정답 목록과 정규화 비교가 일치: {@code {"correct": true, "answer": "..."}}</li>
 * <li>AI-03 판정: {@code {"correct": ..., "reason": "...", "answer": "..."}}</li>
 * <li>AI 실패·시간 초과: {@code {"correct": null, "timeout": true, "answer": "..."}} — 앱은 대표 정답을 보여주고 자가 판정으로 넘어간다</li>
 * </ul>
 * correct·answer는 null이어도 항상 내려주고, reason·timeout은 값이 있을 때만 내려준다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JudgeAnswerResponse(
		@JsonInclude(JsonInclude.Include.ALWAYS) Boolean correct,
		String reason,
		Boolean timeout,
		@JsonInclude(JsonInclude.Include.ALWAYS) String answer
) {

	/** 정규화 비교로 맞음(AI 호출 없음). */
	public static JudgeAnswerResponse matched(String answer) {
		return new JudgeAnswerResponse(true, null, null, answer);
	}

	/** 정규화 후 빈 답이라 AI 없이 틀림(AI 서버와 같은 동작). */
	public static JudgeAnswerResponse blank(String answer) {
		return new JudgeAnswerResponse(false, null, null, answer);
	}

	/** AI-03 판정 결과. */
	public static JudgeAnswerResponse judged(boolean correct, String reason, String answer) {
		return new JudgeAnswerResponse(correct, reason, null, answer);
	}

	/** AI 실패·시간 초과: 자가 판정으로 넘긴다. */
	public static JudgeAnswerResponse timedOut(String answer) {
		return new JudgeAnswerResponse(null, null, true, answer);
	}
}
