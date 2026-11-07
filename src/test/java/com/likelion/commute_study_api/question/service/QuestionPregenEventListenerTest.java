package com.likelion.commute_study_api.question.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.likelion.commute_study_api.common.config.QuestionProperties;
import com.likelion.commute_study_api.common.event.MaterialProcessedEvent;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeAiClient;
import com.likelion.commute_study_api.question.service.QuestionTestFakes.FakeRepository;

class QuestionPregenEventListenerTest {

	@Test
	void 자료_처리_완료_이벤트를_받으면_그_자료의_준비_중_단원마다_문제를_만든다() {
		FakeRepository repository = new FakeRepository();
		FakeAiClient aiClient = new FakeAiClient();
		QuestionPregenService pregenService = new QuestionPregenService(repository, aiClient, new QuestionProperties(),
				Runnable::run, new PregenFailureTracker(), new GeneratedQuestionWriter(repository), Clock.systemUTC());
		repository.addCategory(1L, "PREPARING", "READY");
		repository.addCategory(2L, "PREPARING", "READY");
		repository.addCategory(3L, "AVAILABLE", "READY"); // 이미 끝난 단원은 대상 아님

		new QuestionPregenEventListener(repository, pregenService)
				.onMaterialProcessed(new MaterialProcessedEvent(100L, 10L));

		List<Long> requested = aiClient.requests.stream().map(r -> r.category().categoryId()).toList();
		assertEquals(List.of(1L, 2L), requested);
		assertEquals("AVAILABLE", repository.statusOf(1L));
		assertEquals("AVAILABLE", repository.statusOf(2L));
	}
}
