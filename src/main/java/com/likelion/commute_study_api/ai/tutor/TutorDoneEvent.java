package com.likelion.commute_study_api.ai.tutor;

import com.likelion.commute_study_api.ai.support.AiMeta;

/** event: done 의 data (AI 서버 API 명세 9장). */
public record TutorDoneEvent(AiMeta meta) {
}
