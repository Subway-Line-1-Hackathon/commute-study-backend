package com.likelion.commute_study_api.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnswerNormalizerTest {

	@ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
	@CsvSource(delimiter = '|', value = {
			// 대소문자
			"Queue|queue",
			"QUEUE|queue",
			// 공백(앞뒤·가운데·탭·전각 공백)
			"  큐  |큐",
			"선입 선출|선입선출",
			"선입\t선출|선입선출",
			"선입　선출|선입선출",
			// 문장부호
			"큐.|큐",
			"큐!?|큐",
			"\"큐\"|큐",
			"선입-선출|선입선출",
			"(큐)|큐",
			"first_in|firstin",
			"FIFO(선입선출)|fifo선입선출",
			// NFKC: 전각 영숫자, 호환 문자
			"ＱＵＥＵＥ|queue",
			"１２３|123",
			"①|1",
			// 기호는 남긴다
			"C++|c++",
			"a+b=c|a+b=c",
	})
	void 정규화(String input, String expected) {
		assertEquals(expected, AnswerNormalizer.normalize(input));
	}

	@Test
	void null과_공백_문장부호뿐인_답은_빈_문자열() {
		assertEquals("", AnswerNormalizer.normalize(null));
		assertEquals("", AnswerNormalizer.normalize("   "));
		assertEquals("", AnswerNormalizer.normalize("...!?"));
	}

	@Test
	void 한글은_자모로_쪼개지지_않고_그대로_비교된다() {
		// NFKC는 완성형 한글을 자모로 분해하지 않는다(NFKD와 다름)
		assertEquals("스택", AnswerNormalizer.normalize("스택"));
		assertTrue(AnswerNormalizer.matchesAny("스 택", List.of("스택")));
	}

	@Test
	void 정답_목록_중_하나와_같으면_일치() {
		List<String> accepted = List.of("큐", "Queue");

		assertTrue(AnswerNormalizer.matchesAny(" queue. ", accepted));
		assertTrue(AnswerNormalizer.matchesAny("큐", accepted));
		assertFalse(AnswerNormalizer.matchesAny("대기열", accepted));
		assertFalse(AnswerNormalizer.matchesAny("스택", accepted));
	}

	@Test
	void 빈_답은_정답_목록이_어떻든_불일치() {
		assertFalse(AnswerNormalizer.matchesAny("", List.of("")));
		assertFalse(AnswerNormalizer.matchesAny("  .  ", List.of(".")));
		assertFalse(AnswerNormalizer.matchesAny(null, List.of("큐")));
	}

	@Test
	void 정답_목록이_비어_있으면_불일치() {
		assertFalse(AnswerNormalizer.matchesAny("큐", List.of()));
	}
}
