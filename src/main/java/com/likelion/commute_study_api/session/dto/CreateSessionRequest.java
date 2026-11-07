package com.likelion.commute_study_api.session.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * POST /api/sessions 요청.
 *
 * @param categoryIds      source=categories면 1개 이상 필수. wrongNotes면 생략 가능(주면 그 단원의 오답만)
 * @param studyMinOverride 오늘만 바꾼 시간·'이어서 더 풀기'의 남은 시간. 상한(commute.max-minutes)은 서비스에서 검사
 * @param skipSummary      요약본을 이미 봤으면 true (요약 3분을 빼지 않음)
 */
public record CreateSessionRequest(
		@NotNull SessionSource source,
		List<@NotNull @Positive Long> categoryIds,
		@NotEmpty List<@NotNull @Pattern(regexp = "OX|MC|SHORT", message = "문제 유형은 OX, MC, SHORT 중 하나입니다.") String> questionTypes,
		@NotNull @Min(1) @Max(3) Integer difficulty,
		@Positive Integer studyMinOverride,
		Boolean skipSummary
) {

	@JsonIgnore
	@AssertTrue(message = "단원을 1개 이상 골라야 합니다.")
	public boolean isCategoryIdsValid() {
		if (source != SessionSource.CATEGORIES) {
			return true;
		}
		return categoryIds != null && !categoryIds.isEmpty();
	}

	public List<Long> categoryIdsOrEmpty() {
		return categoryIds == null ? List.of() : categoryIds.stream().distinct().toList();
	}

	public boolean skipSummaryOrFalse() {
		return Boolean.TRUE.equals(skipSummary);
	}
}
