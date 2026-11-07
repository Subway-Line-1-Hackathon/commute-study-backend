package com.likelion.commute_study_api.question.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.likelion.commute_study_api.common.event.MaterialProcessedEvent;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;

/**
 * 자료 처리(슬라이스 8)와는 이 이벤트로만 연결한다 — 서로의 코드를 직접 호출하지 않는다.
 * AFTER_COMMIT: 단원 저장이 커밋되기 전에 읽으면 단원이 안 보이므로 커밋 뒤에 받는다.
 * fallbackExecution: 발행하는 쪽에 트랜잭션이 없어도 받는다.
 */
@Component
public class QuestionPregenEventListener {

	private static final Logger log = LoggerFactory.getLogger(QuestionPregenEventListener.class);

	private final QuestionGenerationRepository repository;
	private final QuestionPregenService pregenService;

	public QuestionPregenEventListener(QuestionGenerationRepository repository, QuestionPregenService pregenService) {
		this.repository = repository;
		this.pregenService = pregenService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onMaterialProcessed(MaterialProcessedEvent event) {
		List<CategoryTarget> categories = repository.findPreparingCategoriesByMaterial(event.materialId());
		log.info("[AI-02] 자료 처리 완료 수신 materialId={} 사전 생성 대상 단원={}개", event.materialId(), categories.size());
		// pregenerate는 바로 돌아온다. 실제 생성은 aiExecutor에서 동시 3개까지 진행된다.
		categories.forEach(pregenService::pregenerate);
	}
}
