package com.likelion.commute_study_api.session.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 세션을 만드는 출처. JSON 값은 명세서 7장의 camelCase(`categories`, `wrongNotes`)다.
 * 이슈 #27 본문의 `wrong_notes`와 다르다 — 사용자 API 필드 표기를 camelCase로 확정한 명세서를 따랐다.
 */
public enum SessionSource {

	CATEGORIES("categories"),
	WRONG_NOTES("wrongNotes");

	private final String value;

	SessionSource(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}
}
