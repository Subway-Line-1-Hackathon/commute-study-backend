package com.likelion.commute_study_api.common.study;

public enum StudyMode {

	CURRICULUM,
	REVIEW;

	/**
	 * 학습 가능 시간이 기준(기본 20분) 이상이면 CURRICULUM, 미만이면 REVIEW.
	 */
	public static StudyMode of(int studyMinutes, int curriculumMinMinutes) {
		return studyMinutes >= curriculumMinMinutes ? CURRICULUM : REVIEW;
	}
}
