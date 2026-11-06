package com.likelion.commute_study_api.ai.material;

import java.util.List;

import com.likelion.commute_study_api.ai.support.AiMeta;

/**
 * AI-01 응답 (AI 서버 API 명세 5장). pages는 반드시 저장해야 한다(material_page, AI-02·AI-05 입력으로 재사용).
 */
public record MaterialProcessResponse(
		int pageCount,
		List<Page> pages,
		List<Category> categories,
		AiMeta meta
) {

	public record Page(int page, String text) {
	}

	public record Category(int order, String name, int pageStart, int pageEnd, String summary) {
	}
}
