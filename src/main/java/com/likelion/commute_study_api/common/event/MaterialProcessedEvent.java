package com.likelion.commute_study_api.common.event;

/**
 * 자료 단원화(AI-01)가 끝나 category가 저장된 뒤 발행. 단원별 문제 사전 생성(AI-02) 트리거용.
 */
public record MaterialProcessedEvent(Long materialId, Long subjectId) {
}
