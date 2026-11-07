package com.likelion.commute_study_api.session.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.dto.SessionSource;
import com.likelion.commute_study_api.session.repository.SessionRepository.CandidateQuestion;

/**
 * 후보 문제에서 세션 문제를 고른다(백엔드 개발 명세서 9.3장). DB·AI를 쓰지 않는 순수 로직이다.
 *
 * <ul>
 *   <li>단원 선택 + CURRICULUM: 안 푼 문제(단원 순서) → 오답노트 OPEN(최근에 틀린 순)</li>
 *   <li>단원 선택 + REVIEW: 오답노트 OPEN(최근에 틀린 순) → 이미 푼 문제(단원 순서)</li>
 *   <li>오답노트 다시 풀기: 오답노트 목록에 보이는 OPEN만(최근에 틀린 순)</li>
 * </ul>
 *
 * 유형별 목표 수를 따로 채우고, 한 유형이 모자라도 다른 유형으로 메우지 않는다(유형 균등).
 * 고른 문제는 유형을 번갈아 섞는다. 예: OX, MC, SHORT, OX, MC, ...
 */
@Component
public class QuestionSelector {

	private static final Comparator<CandidateQuestion> RECENT_WRONG_FIRST = Comparator.comparing(
			CandidateQuestion::lastWrongAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));

	/**
	 * @param questions     세션에 담을 문제(유형을 섞은 순서)
	 * @param missingByType 유형별로 목표보다 모자란 수(0이면 빠짐). 추가 생성 요청의 근거
	 * @param allSolved     고른 유형의 후보를 모두 푼 상태(안 푼 문제가 하나도 없음)
	 */
	public record Selection(List<CandidateQuestion> questions, Map<String, Integer> missingByType, boolean allSolved) {

		public int missingTotal() {
			return missingByType.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	public Selection select(StudyMode mode, SessionSource source, Map<String, Integer> countsByType,
			List<CandidateQuestion> candidates) {
		Map<String, List<CandidateQuestion>> pickedByType = new LinkedHashMap<>();
		Map<String, Integer> missingByType = new LinkedHashMap<>();

		countsByType.forEach((type, target) -> {
			List<CandidateQuestion> ofType = candidates.stream().filter(q -> q.type().equals(type)).toList();
			List<CandidateQuestion> picked = prioritize(mode, source, ofType).limit(target).toList();
			pickedByType.put(type, picked);
			if (picked.size() < target) {
				missingByType.put(type, target - picked.size());
			}
		});

		boolean allSolved = candidates.stream()
				.filter(q -> countsByType.containsKey(q.type()))
				.allMatch(CandidateQuestion::solved);

		return new Selection(interleave(pickedByType), missingByType, allSolved);
	}

	private static Stream<CandidateQuestion> prioritize(StudyMode mode, SessionSource source,
			List<CandidateQuestion> ofType) {
		if (source == SessionSource.WRONG_NOTES) {
			return ofType.stream().filter(CandidateQuestion::wrongListed).sorted(RECENT_WRONG_FIRST);
		}
		Predicate<CandidateQuestion> wrong = q -> q.solved() && q.wrongOpen();
		if (mode == StudyMode.REVIEW) {
			return Stream.concat(
					ofType.stream().filter(wrong).sorted(RECENT_WRONG_FIRST),
					ofType.stream().filter(q -> q.solved() && !q.wrongOpen()));
		}
		return Stream.concat(
				ofType.stream().filter(q -> !q.solved()),
				ofType.stream().filter(wrong).sorted(RECENT_WRONG_FIRST));
	}

	/** 유형별 목록을 한 개씩 번갈아 꺼낸다. 짧은 목록이 끝나면 남은 유형끼리 계속 번갈아 낸다. */
	private static List<CandidateQuestion> interleave(Map<String, List<CandidateQuestion>> pickedByType) {
		List<CandidateQuestion> mixed = new ArrayList<>();
		int longest = pickedByType.values().stream().mapToInt(List::size).max().orElse(0);
		for (int i = 0; i < longest; i++) {
			for (List<CandidateQuestion> picked : pickedByType.values()) {
				if (i < picked.size()) {
					mixed.add(picked.get(i));
				}
			}
		}
		return mixed;
	}

	/**
	 * 모자란 수를 추가 생성할 단원에 고르게 나눈다. 나머지는 유형마다 시작 단원을 바꿔 가며 1개씩 더 준다
	 * (한 단원에만 몰리지 않게). 결과에 없는 단원은 요청할 것이 없다는 뜻이다.
	 *
	 * @param categoryIds 추가 생성이 가능한 단원(PREPARING·EXPIRED 제외는 호출하는 쪽이 한다), 단원 순서
	 */
	public static Map<Long, List<TypeRequest>> distributeMissing(Map<String, Integer> missingByType,
			List<Long> categoryIds, int difficulty) {
		Map<Long, List<TypeRequest>> requests = new LinkedHashMap<>();
		if (categoryIds.isEmpty()) {
			return requests;
		}
		int n = categoryIds.size();
		int offset = 0;
		for (Map.Entry<String, Integer> entry : missingByType.entrySet()) {
			int missing = entry.getValue();
			int base = missing / n;
			int remainder = missing % n;
			for (int i = 0; i < n; i++) {
				int extra = Math.floorMod(i - offset, n) < remainder ? 1 : 0;
				int count = base + extra;
				if (count > 0) {
					requests.computeIfAbsent(categoryIds.get(i), id -> new ArrayList<>())
							.add(new TypeRequest(entry.getKey(), difficulty, count));
				}
			}
			offset = (offset + remainder) % n;
		}
		return requests;
	}
}
