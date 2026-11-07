package com.likelion.commute_study_api.question.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.GeneratedQuestion;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;

/** AI-02 응답을 저장한다. 근거 쪽수가 단원 범위 밖인 문제는 버린다(AI 서버 API 명세 6장). */
@Component
public class GeneratedQuestionWriter {

	private static final Logger log = LoggerFactory.getLogger(GeneratedQuestionWriter.class);

	private final QuestionGenerationRepository repository;

	public GeneratedQuestionWriter(QuestionGenerationRepository repository) {
		this.repository = repository;
	}

	/** 실제로 저장한 개수를 돌려준다. */
	public int save(CategoryTarget category, QuestionGenerateResponse response, String origin) {
		List<GeneratedQuestion> generated = response.questions() == null ? List.of() : response.questions();
		List<GeneratedQuestion> valid = generated.stream()
				.filter(q -> category.isPageInRange(q.sourcePage()))
				.toList();
		int dropped = generated.size() - valid.size();
		if (dropped > 0) {
			log.warn("[AI-02] 근거 쪽수가 단원 범위 밖이라 버림 categoryId={} dropped={}", category.id(), dropped);
		}
		if (!valid.isEmpty()) {
			repository.insertQuestions(category.id(), origin, valid);
		}
		log.info("[AI-02] 문제 저장 categoryId={} origin={} saved={} shortfall={} rejected={}",
				category.id(), origin, valid.size(), response.shortfall(), response.rejected());
		return valid.size();
	}
}
