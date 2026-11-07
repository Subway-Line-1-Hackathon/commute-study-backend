package com.likelion.commute_study_api.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.service.SessionPlanCalculator.SessionPlan;

class SessionPlanCalculatorTest {

	private final SessionPlanCalculator calculator = new SessionPlanCalculator(new StudyProperties());

	// ---- 이슈 #27 완료 조건의 예시 ----

	@Test
	void 오십분_객관식은_상한_30문제() {
		SessionPlan plan = calculator.plan(50, null, List.of("MC"), false);

		assertEquals(StudyMode.CURRICULUM, plan.mode());
		assertEquals(30, plan.questionCount()); // (50 - 3) / 1 = 47 → 상한 30
	}

	@Test
	void 삼십분_객관식은_27문제() {
		SessionPlan plan = calculator.plan(30, null, List.of("MC"), false);

		assertEquals(27, plan.questionCount()); // (30 - 3) / 1
		assertEquals(3, plan.summaryMin());
	}

	@Test
	void 이십분_객관식과_단답은_평균_시간으로_13문제() {
		SessionPlan plan = calculator.plan(20, null, List.of("MC", "SHORT"), false);

		assertEquals(StudyMode.CURRICULUM, plan.mode());
		assertEquals(13, plan.questionCount()); // (20 - 3) / 1.25 = 13.6 → 13
		assertEquals(Map.of("MC", 7, "SHORT", 6), plan.countsByType());
	}

	@Test
	void 십구분_객관식은_REVIEW이고_요약을_빼지_않아_19문제() {
		SessionPlan plan = calculator.plan(19, null, List.of("MC"), false);

		assertEquals(StudyMode.REVIEW, plan.mode());
		assertEquals(0, plan.summaryMin());
		assertEquals(19, plan.questionCount());
	}

	// ---- 그 밖의 규칙 ----

	@Test
	void studyMinOverride가_있으면_편도_시간_대신_쓴다() {
		SessionPlan plan = calculator.plan(50, 19, List.of("MC"), false);

		assertEquals(19, plan.studyMin());
		assertEquals(StudyMode.REVIEW, plan.mode());
		assertEquals(19, plan.questionCount());
	}

	@Test
	void skipSummary면_요약_시간을_빼지_않는다() {
		SessionPlan plan = calculator.plan(25, null, List.of("MC"), true);

		assertEquals(0, plan.summaryMin());
		assertEquals(25, plan.questionCount());
	}

	@Test
	void OX는_문제당_30초라_금방_상한에_닿는다() {
		assertEquals(30, calculator.plan(20, null, List.of("OX"), false).questionCount()); // 17 / 0.5 = 34 → 30
	}

	@Test
	void 세_유형을_고르면_균등하게_나누고_나머지는_앞_유형부터() {
		// 평균 (0.5 + 1 + 1.5) / 3 = 1분 → (20 - 3) / 1 = 17
		SessionPlan plan = calculator.plan(20, null, List.of("OX", "MC", "SHORT"), false);

		assertEquals(17, plan.questionCount());
		assertEquals(Map.of("OX", 6, "MC", 6, "SHORT", 5), plan.countsByType());
	}

	@Test
	void 유형은_대소문자를_가리지_않고_중복은_하나로_본다() {
		SessionPlan plan = calculator.plan(30, null, List.of("mc", "MC"), false);

		assertEquals(27, plan.questionCount());
		assertEquals(Map.of("MC", 27), plan.countsByType());
	}

	@Test
	void 설정값을_바꾸면_계산에_반영된다() {
		StudyProperties props = new StudyProperties();
		props.setMaxQuestionsPerSession(10);
		SessionPlan plan = new SessionPlanCalculator(props).plan(50, null, List.of("MC"), false);

		assertEquals(10, plan.questionCount());
	}

	@Test
	void 유형이_없거나_모르는_유형이면_예외() {
		assertThrows(IllegalArgumentException.class, () -> calculator.plan(30, null, List.of(), false));
		assertThrows(IllegalArgumentException.class, () -> calculator.plan(30, null, List.of("ESSAY"), false));
	}
}
