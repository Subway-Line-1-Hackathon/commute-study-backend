package com.likelion.commute_study_api.common.event;

import java.util.UUID;

/**
 * 세션이 COMPLETED/ENDED가 된 뒤 발행. 통학 후 피드백(AI-06) 호출 트리거용.
 * status/reason은 세션 도메인의 enum이 생기기 전까지 문자열로 둔다(session.status, POST /sessions/{id}/end의 reason).
 */
public record SessionFinishedEvent(Long sessionId, UUID userId, String status, String reason) {
}
