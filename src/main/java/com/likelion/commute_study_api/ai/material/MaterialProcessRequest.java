package com.likelion.commute_study_api.ai.material;

import java.util.List;

/**
 * AI-01 요청 (AI 서버 API 명세 5장). fileUrls는 Storage 서명 URL, 호출 직전에 새로 만든다.
 */
public record MaterialProcessRequest(
		Long materialId,
		String fileType,
		String kind,
		String fileName,
		List<String> fileUrls
) {
}
