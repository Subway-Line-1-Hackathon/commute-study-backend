package com.likelion.commute_study_api.answer;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 단답 정답 비교용 정규화(명세서 9.7, AI 서버 ↔ 백엔드 API 명세 7장, 합의 #6).
 * AI 서버와 같은 규칙: 유니코드 NFKC 정규화 → 소문자 → 공백·문장부호 제거.
 * 규칙이 같아야 백엔드에서 일치를 먼저 잡아 AI 호출(AI-03)을 아낄 수 있다.
 *
 * <p>문장부호는 유니코드 문장부호(\p{P}: . , ! ? ' " - _ ( ) 등)만 지운다.
 * 기호(\p{S}: + = $ 등)는 "C++"처럼 답의 일부일 수 있어 남긴다.
 */
public final class AnswerNormalizer {

	/** 공백(\s, 유니코드 공백 \p{Z})과 문장부호(\p{P}). */
	private static final Pattern REMOVED = Pattern.compile("[\\s\\p{Z}\\p{P}]+");

	private AnswerNormalizer() {
	}

	/** null이면 빈 문자열. */
	public static String normalize(String value) {
		if (value == null) {
			return "";
		}
		String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
		return REMOVED.matcher(nfkc.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	/** 정규화한 답이 정답 목록 중 하나와 같으면 true. 정규화 후 빈 답은 항상 false. */
	public static boolean matchesAny(String answer, Collection<String> acceptedAnswers) {
		String normalized = normalize(answer);
		if (normalized.isEmpty()) {
			return false;
		}
		for (String accepted : acceptedAnswers) {
			if (normalized.equals(normalize(accepted))) {
				return true;
			}
		}
		return false;
	}
}
