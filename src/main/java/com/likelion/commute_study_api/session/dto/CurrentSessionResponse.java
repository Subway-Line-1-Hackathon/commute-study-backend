package com.likelion.commute_study_api.session.dto;

import java.util.List;

/**
 * GET /api/sessions/current 응답(이어하기 팝업).
 * answered = 서버에 저장된 응답 수. 앱은 세션 문제 순서에서 answered번째 다음 문제부터 이어 푼다.
 */
public record CurrentSessionResponse(long sessionId, List<String> categoryNames, int answered, int total) {
}
