package com.likelion.commute_study_api.answer.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

class JudgeAnswerDtoTest {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 정규화_일치는_correct만() {
		assertEquals("{\"correct\":true}", mapper.writeValueAsString(JudgeAnswerResponse.matched()));
	}

	@Test
	void AI_판정은_correct와_reason() {
		assertEquals("{\"correct\":false,\"reason\":\"다른 개념이에요.\"}",
				mapper.writeValueAsString(JudgeAnswerResponse.judged(false, "다른 개념이에요.")));
	}

	@Test
	void 시간_초과는_correct_null을_빼지_않고_내려준다() {
		assertEquals("{\"correct\":null,\"timeout\":true}", mapper.writeValueAsString(JudgeAnswerResponse.timedOut()));
	}

	@Test
	void 답은_30자까지() {
		assertTrue(violations(new JudgeAnswerRequest(1L, "가".repeat(30))).isEmpty());
		assertEquals(Set.of("answer"), violations(new JudgeAnswerRequest(1L, "가".repeat(31))));
	}

	@Test
	void 문제_id와_답은_필수() {
		assertEquals(Set.of("questionId", "answer"), violations(new JudgeAnswerRequest(null, null)));
		assertEquals(Set.of("questionId"), violations(new JudgeAnswerRequest(0L, "큐")));
	}

	@Test
	void 빈_답은_검증에서_막지_않는다() {
		// AI 서버처럼 오답으로 판정한다(서비스에서 처리)
		assertTrue(violations(new JudgeAnswerRequest(1L, "")).isEmpty());
	}

	private Set<String> violations(JudgeAnswerRequest request) {
		return validator.validate(request).stream()
				.map(ConstraintViolation::getPropertyPath)
				.map(Object::toString)
				.collect(Collectors.toSet());
	}
}
