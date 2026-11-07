package com.likelion.commute_study_api.session.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.SessionQuestion;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.Shortage;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

class CreateSessionDtoTest {

	private static final JsonMapper mapper = JsonMapper.builder().build();
	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void setUp() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void tearDown() {
		factory.close();
	}

	private static CreateSessionRequest read(String json) {
		return mapper.readValue(json, CreateSessionRequest.class);
	}

	private static Set<String> violatedFields(CreateSessionRequest request) {
		return validator.validate(request).stream()
				.map(ConstraintViolation::getPropertyPath)
				.map(Object::toString)
				.collect(Collectors.toSet());
	}

	// ---- 요청 ----

	@Test
	void 명세서_예시_요청을_읽는다() {
		CreateSessionRequest request = read("""
				{"source":"categories","categoryIds":[7,8],"questionTypes":["MC","SHORT"],
				 "difficulty":2,"studyMinOverride":15,"skipSummary":true}
				""");

		assertEquals(SessionSource.CATEGORIES, request.source());
		assertEquals(List.of(7L, 8L), request.categoryIds());
		assertTrue(request.skipSummaryOrFalse());
		assertTrue(violatedFields(request).isEmpty());
	}

	@Test
	void 오답노트_다시_풀기는_단원_없이도_유효하다() {
		CreateSessionRequest request = read("""
				{"source":"wrongNotes","questionTypes":["OX"],"difficulty":1}
				""");

		assertEquals(SessionSource.WRONG_NOTES, request.source());
		assertEquals(List.of(), request.categoryIdsOrEmpty());
		assertFalse(request.skipSummaryOrFalse());
		assertTrue(violatedFields(request).isEmpty());
	}

	@Test
	void 모르는_source_값은_읽기_단계에서_거부된다() {
		// GlobalExceptionHandler가 HttpMessageNotReadableException으로 받아 400 VALIDATION_ERROR가 된다.
		assertThrows(Exception.class, () -> read("""
				{"source":"wrong_notes","questionTypes":["OX"],"difficulty":1}
				"""));
	}

	@Test
	void 단원_선택인데_단원이_없으면_위반() {
		CreateSessionRequest request = read("""
				{"source":"categories","categoryIds":[],"questionTypes":["MC"],"difficulty":2}
				""");

		assertEquals(Set.of("categoryIdsValid"), violatedFields(request));
	}

	@Test
	void 필수값과_범위를_검사한다() {
		CreateSessionRequest request = read("""
				{"categoryIds":[0],"questionTypes":["ESSAY"],"difficulty":4,"studyMinOverride":0}
				""");

		Set<String> fields = violatedFields(request);
		assertTrue(fields.contains("source"));
		assertTrue(fields.stream().anyMatch(f -> f.startsWith("categoryIds[")));
		assertTrue(fields.stream().anyMatch(f -> f.startsWith("questionTypes[")));
		assertTrue(fields.contains("difficulty"));
		assertTrue(fields.contains("studyMinOverride"));
	}

	@Test
	void 유형이_비어_있으면_위반() {
		CreateSessionRequest request = read("""
				{"source":"categories","categoryIds":[1],"questionTypes":[],"difficulty":2}
				""");

		assertEquals(Set.of("questionTypes"), violatedFields(request));
	}

	@Test
	void 중복된_단원은_하나로_본다() {
		CreateSessionRequest request = read("""
				{"source":"categories","categoryIds":[3,3,1],"questionTypes":["MC"],"difficulty":2}
				""");

		assertEquals(List.of(3L, 1L), request.categoryIdsOrEmpty());
	}

	// ---- 응답 ----

	@Test
	void 단답은_정답과_해설을_내려주지_않는다() {
		SessionQuestion shortAnswer = SessionQuestion.of(1, "SHORT", "스택의 특징은?", List.of(), 2, "LIFO", "해설");
		SessionQuestion mc = SessionQuestion.of(2, "MC", "큐는?", List.of("LIFO", "FIFO"), 3, "FIFO", "해설");

		assertNull(shortAnswer.answer());
		assertNull(shortAnswer.explanation());
		assertNull(shortAnswer.choices());
		assertEquals("FIFO", mc.answer());
		assertEquals(List.of("LIFO", "FIFO"), mc.choices());
	}

	@Test
	void 선택_필드가_없으면_JSON에서_빠진다() {
		CreateSessionResponse response = new CreateSessionResponse(null, StudyMode.REVIEW, 19, 0,
				null, null, true, List.of());

		String json = mapper.writeValueAsString(response);

		assertFalse(json.contains("sessionId"));
		assertFalse(json.contains("summary"));
		assertFalse(json.contains("shortage"));
		assertTrue(json.contains("\"noReviewItems\":true"));
		assertTrue(json.contains("\"mode\":\"REVIEW\""));
	}

	@Test
	void shortage가_있으면_세_필드를_내려준다() {
		CreateSessionResponse response = new CreateSessionResponse(10L, StudyMode.CURRICULUM, 30, 12,
				"요약", new Shortage(27, 12, true), null, List.of());

		String json = mapper.writeValueAsString(response);

		assertTrue(json.contains("\"shortage\":{\"target\":27,\"available\":12,\"allSolved\":true}"));
		assertFalse(json.contains("noReviewItems"));
	}
}
