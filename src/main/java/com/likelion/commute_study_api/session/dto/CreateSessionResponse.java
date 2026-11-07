package com.likelion.commute_study_api.session.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.commute_study_api.common.study.StudyMode;

/**
 * POST /api/sessions 응답. 값이 없는 선택 필드(summary, shortage, noReviewItems)는 JSON에서 빠진다.
 *
 * @param summary       CURRICULUM이면 고른 단원 요약을 단원 순서대로 이어 붙인 것. REVIEW·skipSummary면 없음
 * @param shortage      목표보다 문제가 적을 때만
 * @param noReviewItems REVIEW인데 푼 문제가 하나도 없을 때만 true
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateSessionResponse(
		long sessionId,
		StudyMode mode,
		int studyMin,
		int questionCount,
		String summary,
		Shortage shortage,
		Boolean noReviewItems,
		List<SessionQuestion> questions
) {

	/**
	 * @param target    계산한 목표 문제 수
	 * @param available 실제로 담은 문제 수
	 * @param allSolved 고른 범위의 문제를 모두 푼 경우 true (프론트가 "다음 단원 풀기"를 제안)
	 */
	public record Shortage(int target, int available, boolean allSolved) {
	}

	/**
	 * 세션 문제. O/X·객관식은 앱에서 바로 채점하고 오프라인으로 저장해야 해서 answer·explanation을 담는다.
	 * 단답은 AI 판정(AI-03)을 거치므로 담지 않는다(명세서 7장 임시 결정).
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SessionQuestion(
			long id,
			String type,
			String stem,
			List<String> choices,
			Integer sourcePage,
			String answer,
			String explanation
	) {

		public static SessionQuestion of(long id, String type, String stem, List<String> choices,
				Integer sourcePage, String answer, String explanation) {
			boolean includeAnswer = !"SHORT".equals(type);
			return new SessionQuestion(id, type, stem,
					choices == null || choices.isEmpty() ? null : List.copyOf(choices),
					sourcePage,
					includeAnswer ? answer : null,
					includeAnswer ? explanation : null);
		}
	}
}
