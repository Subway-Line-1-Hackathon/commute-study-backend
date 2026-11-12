package com.likelion.commute_study_api.common.study;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StudyModeTest {

	@Test
	void 기준보다_1분_적으면_REVIEW() {
		assertEquals(StudyMode.REVIEW, StudyMode.of(19, 20));
	}

	@Test
	void 기준과_같으면_CURRICULUM() {
		assertEquals(StudyMode.CURRICULUM, StudyMode.of(20, 20));
	}

	@Test
	void 기준보다_크면_CURRICULUM() {
		assertEquals(StudyMode.CURRICULUM, StudyMode.of(50, 20));
	}

	@Test
	void 통학_10분이면_REVIEW() {
		assertEquals(StudyMode.REVIEW, StudyMode.of(10, 11));
	}

	@Test
	void 통학_11분이면_CURRICULUM() {
		assertEquals(StudyMode.CURRICULUM, StudyMode.of(11, 11));
	}
}
