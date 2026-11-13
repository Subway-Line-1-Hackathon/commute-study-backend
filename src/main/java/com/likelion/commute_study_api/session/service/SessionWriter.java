package com.likelion.commute_study_api.session.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.session.repository.SessionRepository;
import com.likelion.commute_study_api.session.dto.SessionSource;

/**
 * 세션 저장만 한 트랜잭션으로 묶는다. SessionService.create 전체를 트랜잭션으로 묶으면
 * AI 추가 생성을 기다리는 동안(최대 1분) DB 연결을 잡고 있게 되므로 저장 부분만 따로 뺐다.
 */
@Component
public class SessionWriter {

	private final SessionRepository sessionRepository;

	public SessionWriter(SessionRepository sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	/** 출발하지 않은 이전 READY 세션을 지우고 새 세션과 문제 순서를 저장한다. */
	@Transactional
	public long save(UUID userId, List<Long> categoryIds, List<String> questionTypes, int difficulty, int studyMin,
			StudyMode mode, SessionSource source, List<Long> questionIds) {
		if (!sessionRepository.lockActiveMaterialsForSelection(userId, questionIds, categoryIds)) {
			throw new ApiException(CommonErrorCode.STATE_CONFLICT, "선택한 자료가 삭제되어 세션을 다시 만들어야 합니다.");
		}
		sessionRepository.deleteReadySessions(userId);
		long sessionId = sessionRepository.insertSession(userId, categoryIds, questionTypes, difficulty, studyMin,
				mode.name(), source);
		sessionRepository.insertSessionQuestions(sessionId, questionIds);
		return sessionId;
	}
}
