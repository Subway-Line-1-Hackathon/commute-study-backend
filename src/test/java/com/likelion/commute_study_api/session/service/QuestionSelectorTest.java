package com.likelion.commute_study_api.session.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.dto.SessionSource;
import com.likelion.commute_study_api.session.repository.SessionRepository.CandidateQuestion;
import com.likelion.commute_study_api.session.service.QuestionSelector.Selection;

class QuestionSelectorTest {

	private static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

	private final QuestionSelector selector = new QuestionSelector();
	private final List<CandidateQuestion> candidates = new ArrayList<>();

	// ---- 후보 만들기 ----

	private void unsolved(long id, String type) {
		candidates.add(q(id, type, false, false, false, null));
	}

	private void solved(long id, String type) {
		candidates.add(q(id, type, true, false, false, null));
	}

	/** 오답노트 OPEN. minutesAgo가 작을수록 최근에 틀린 것. */
	private void wrong(long id, String type, int minutesAgo, boolean listed) {
		candidates.add(q(id, type, true, true, listed, T.minusSeconds(60L * minutesAgo)));
	}

	private static CandidateQuestion q(long id, String type, boolean solved, boolean wrongOpen, boolean listed,
			Instant lastWrongAt) {
		return new CandidateQuestion(id, 1L, type, "stem " + id, List.of(), 1, "O", "해설",
				solved, wrongOpen, listed, lastWrongAt);
	}

	private static List<Long> ids(Selection selection) {
		return selection.questions().stream().map(CandidateQuestion::id).toList();
	}

	private static Map<String, Integer> counts(Object... typeAndCount) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < typeAndCount.length; i += 2) {
			counts.put((String) typeAndCount[i], (Integer) typeAndCount[i + 1]);
		}
		return counts;
	}

	// ---- 단원 선택 + CURRICULUM ----

	@Test
	void 안_푼_문제를_먼저_고르고_모자라면_오답을_최근_순으로() {
		unsolved(1, "MC");
		wrong(2, "MC", 30, true);
		wrong(3, "MC", 5, true);
		solved(4, "MC"); // 맞힌 문제는 CURRICULUM에서 다시 내지 않는다
		unsolved(5, "MC");

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES, counts("MC", 10), candidates);

		assertEquals(List.of(1L, 5L, 3L, 2L), ids(s));
		assertEquals(Map.of("MC", 6), s.missingByType());
		assertEquals(6, s.missingTotal());
		assertFalse(s.allSolved());
	}

	@Test
	void 충분하면_목표만큼만_고르고_모자란_것이_없다() {
		for (long id = 1; id <= 5; id++) {
			unsolved(id, "OX");
		}

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES, counts("OX", 3), candidates);

		assertEquals(List.of(1L, 2L, 3L), ids(s));
		assertTrue(s.missingByType().isEmpty());
	}

	@Test
	void 고른_유형만_뽑고_유형을_번갈아_섞는다() {
		unsolved(1, "MC");
		unsolved(2, "MC");
		unsolved(3, "MC");
		unsolved(4, "SHORT");
		unsolved(5, "SHORT");
		unsolved(6, "OX"); // 고르지 않은 유형

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES,
				counts("MC", 3, "SHORT", 2), candidates);

		assertEquals(List.of(1L, 4L, 2L, 5L, 3L), ids(s));
	}

	@Test
	void 한_유형이_모자라도_다른_유형으로_메우지_않는다() {
		unsolved(1, "MC");
		unsolved(2, "MC");
		unsolved(3, "MC");
		unsolved(4, "SHORT");

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES,
				counts("MC", 2, "SHORT", 2), candidates);

		assertEquals(List.of(1L, 4L, 2L), ids(s));
		assertEquals(Map.of("SHORT", 1), s.missingByType());
	}

	@Test
	void 고른_유형을_모두_풀었으면_allSolved() {
		solved(1, "MC");
		wrong(2, "MC", 1, true);
		unsolved(3, "OX"); // 고르지 않은 유형은 상관없다

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES, counts("MC", 5), candidates);

		assertTrue(s.allSolved());
		assertEquals(List.of(2L), ids(s));
	}

	@Test
	void 후보가_없으면_전부_모자라다() {
		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.CATEGORIES,
				counts("OX", 4, "MC", 3), candidates);

		assertTrue(s.questions().isEmpty());
		assertEquals(Map.of("OX", 4, "MC", 3), s.missingByType());
	}

	// ---- REVIEW ----

	@Test
	void REVIEW는_오답을_먼저_그다음_푼_문제이고_안_푼_문제는_내지_않는다() {
		unsolved(1, "MC");
		solved(2, "MC");
		wrong(3, "MC", 60, true);
		wrong(4, "MC", 1, false); // 목록 밖이어도 OPEN이면 복습 대상
		solved(5, "MC");

		Selection s = selector.select(StudyMode.REVIEW, SessionSource.CATEGORIES, counts("MC", 19), candidates);

		assertEquals(List.of(4L, 3L, 2L, 5L), ids(s));
	}

	@Test
	void REVIEW에서_푼_문제가_없으면_빈_목록() {
		unsolved(1, "MC");

		Selection s = selector.select(StudyMode.REVIEW, SessionSource.CATEGORIES, counts("MC", 19), candidates);

		assertTrue(s.questions().isEmpty());
	}

	// ---- 오답노트 다시 풀기 ----

	@Test
	void 오답노트_다시_풀기는_목록에_보이는_OPEN만_최근_순으로() {
		unsolved(1, "OX");
		solved(2, "OX");
		wrong(3, "OX", 10, true);
		wrong(4, "OX", 2, true);
		wrong(5, "OX", 1, false); // 목록 상한 밖

		Selection s = selector.select(StudyMode.CURRICULUM, SessionSource.WRONG_NOTES, counts("OX", 30), candidates);

		assertEquals(List.of(4L, 3L), ids(s));
	}

	// ---- 추가 생성 분배 ----

	@Test
	void 모자란_수를_단원에_고르게_나눈다() {
		Map<Long, List<TypeRequest>> requests = QuestionSelector.distributeMissing(
				counts("MC", 6), List.of(7L, 8L), 3);

		assertEquals(Map.of(
				7L, List.of(new TypeRequest("MC", 3, 3)),
				8L, List.of(new TypeRequest("MC", 3, 3))), requests);
	}

	@Test
	void 나머지는_유형마다_다른_단원부터_준다() {
		Map<Long, List<TypeRequest>> requests = QuestionSelector.distributeMissing(
				counts("MC", 1, "SHORT", 1), List.of(7L, 8L), 2);

		assertEquals(Map.of(
				7L, List.of(new TypeRequest("MC", 2, 1)),
				8L, List.of(new TypeRequest("SHORT", 2, 1))), requests);
	}

	@Test
	void 단원이_하나면_전부_그_단원에() {
		Map<Long, List<TypeRequest>> requests = QuestionSelector.distributeMissing(
				counts("OX", 5, "MC", 2), List.of(7L), 1);

		assertEquals(Map.of(7L, List.of(new TypeRequest("OX", 1, 5), new TypeRequest("MC", 1, 2))), requests);
	}

	@Test
	void 생성_가능한_단원이_없으면_요청도_없다() {
		assertTrue(QuestionSelector.distributeMissing(counts("OX", 5), List.of(), 1).isEmpty());
	}
}
