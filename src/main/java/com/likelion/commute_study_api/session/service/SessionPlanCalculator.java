package com.likelion.commute_study_api.session.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.common.config.StudyProperties;
import com.likelion.commute_study_api.common.study.StudyMode;

/**
 * 학습 가능 시간으로 세션 모드와 문제 수를 정한다(백엔드 개발 명세서 9.1, 9.2장).
 *
 * <pre>
 * studyMin       = studyMinOverride ?: 편도 시간
 * mode           = studyMin >= 11 ? CURRICULUM : REVIEW
 * summaryMin     = CURRICULUM이고 skipSummary가 아니면 3, 아니면 0
 * questionCount  = min(30, floor((studyMin - summaryMin) / 선택한 유형의 평균 소요 시간))
 * </pre>
 *
 * 난이도는 시간에 영향을 주지 않는다. 문제 수는 선택한 유형에 균등하게 나눈다.
 */
@Component
public class SessionPlanCalculator {

	private final StudyProperties studyProperties;

	public SessionPlanCalculator(StudyProperties studyProperties) {
		this.studyProperties = studyProperties;
	}

	/**
	 * @param countsByType 유형별 목표 문제 수. 키는 요청 순서를 지킨 OX/MC/SHORT
	 */
	public record SessionPlan(int studyMin, StudyMode mode, int summaryMin, int questionCount,
			Map<String, Integer> countsByType) {
	}

	/**
	 * @param oneWayMin        통학 설정의 편도 시간
	 * @param studyMinOverride 오늘만 바꾼 시간(없으면 null). 저장된 설정은 바꾸지 않는다
	 * @param questionTypes    OX/MC/SHORT 중 1개 이상(대소문자 무관, 중복은 하나로 본다)
	 * @param skipSummary      '이어서 더 풀기'처럼 요약본을 이미 본 경우 true
	 */
	public SessionPlan plan(int oneWayMin, Integer studyMinOverride, List<String> questionTypes, boolean skipSummary) {
		int studyMin = studyMinOverride != null ? studyMinOverride : oneWayMin;
		StudyMode mode = StudyMode.of(studyMin, studyProperties.getCurriculumMinMinutes());
		int summaryMin = mode == StudyMode.CURRICULUM && !skipSummary ? studyProperties.getSummaryMinutes() : 0;

		List<String> types = normalize(questionTypes);
		int questionCount = Math.min(studyProperties.getMaxQuestionsPerSession(),
				countByTime(studyMin - summaryMin, types));

		return new SessionPlan(studyMin, mode, summaryMin, questionCount, splitEvenly(questionCount, types));
	}

	private static List<String> normalize(List<String> questionTypes) {
		if (questionTypes == null || questionTypes.isEmpty()) {
			throw new IllegalArgumentException("문제 유형을 1개 이상 골라야 합니다.");
		}
		LinkedHashSet<String> types = new LinkedHashSet<>();
		for (String type : questionTypes) {
			types.add(type.toUpperCase(Locale.ROOT));
		}
		return List.copyOf(types);
	}

	/**
	 * floor(availableMin / (유형 시간 합 / 유형 수)) = floor(availableMin * 유형 수 / 유형 시간 합).
	 * 0.1 같은 값이 설정에 들어와도 오차가 나지 않게 BigDecimal로 계산한다.
	 */
	private int countByTime(int availableMin, List<String> types) {
		if (availableMin <= 0) {
			return 0;
		}
		BigDecimal minutesSum = BigDecimal.ZERO;
		for (String type : types) {
			minutesSum = minutesSum.add(BigDecimal.valueOf(minutesPerQuestion(type)));
		}
		return BigDecimal.valueOf(availableMin)
				.multiply(BigDecimal.valueOf(types.size()))
				.divide(minutesSum, 0, RoundingMode.FLOOR)
				.intValueExact();
	}

	private double minutesPerQuestion(String type) {
		Double minutes = studyProperties.getMinutesPerQuestion().get(type.toLowerCase(Locale.ROOT));
		if (minutes == null || minutes <= 0) {
			throw new IllegalArgumentException("지원하지 않는 문제 유형입니다: " + type);
		}
		return minutes;
	}

	/** 나머지는 요청한 순서대로 앞 유형부터 1개씩 더 준다. 예: 13문제 MC+SHORT → MC 7, SHORT 6. */
	private static Map<String, Integer> splitEvenly(int total, List<String> types) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		int base = total / types.size();
		int remainder = total % types.size();
		for (int i = 0; i < types.size(); i++) {
			counts.put(types.get(i), base + (i < remainder ? 1 : 0));
		}
		return counts;
	}
}
