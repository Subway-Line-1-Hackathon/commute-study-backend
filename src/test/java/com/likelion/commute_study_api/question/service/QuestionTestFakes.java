package com.likelion.commute_study_api.question.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.likelion.commute_study_api.ai.question.QuestionAiClient;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest;
import com.likelion.commute_study_api.ai.question.QuestionGenerateRequest.TypeRequest;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.GeneratedQuestion;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.Rejected;
import com.likelion.commute_study_api.ai.question.QuestionGenerateResponse.Shortfall;
import com.likelion.commute_study_api.ai.support.AiPage;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository;
import com.likelion.commute_study_api.question.repository.QuestionGenerationRepository.CategoryTarget;

/** DB와 AI 서버 없이 문제 생성 흐름을 시험하기 위한 가짜들. */
final class QuestionTestFakes {

	private QuestionTestFakes() {
	}

	record StoredQuestion(long categoryId, String type, int difficulty, String stem, String origin) {
	}

	/** 메모리에 저장하는 Repository. countQuestions가 실제 저장 결과를 세므로 부족분 계산까지 검증된다. */
	static class FakeRepository extends QuestionGenerationRepository {

		final Map<Long, CategoryTarget> categories = new HashMap<>();
		final List<StoredQuestion> questions = new CopyOnWriteArrayList<>();
		List<AiPage> pages = List.of(new AiPage(1, "스택은 LIFO 구조다."), new AiPage(2, "큐는 FIFO 구조다."));

		FakeRepository() {
			super(null);
		}

		CategoryTarget addCategory(long id, String questionStatus, String materialStatus) {
			CategoryTarget category = new CategoryTarget(id, 100L, "스택과 큐", 1, 3, questionStatus, materialStatus);
			categories.put(id, category);
			return category;
		}

		String statusOf(long categoryId) {
			return categories.get(categoryId).questionStatus();
		}

		void seedQuestions(long categoryId, String type, int difficulty, int count) {
			for (int i = 0; i < count; i++) {
				questions.add(new StoredQuestion(categoryId, type, difficulty, "기존 " + type + " " + i, "PREGEN"));
			}
		}

		@Override
		public boolean lockActiveMaterialForCategory(long categoryId) { return categories.containsKey(categoryId); }

		@Override
		public Optional<CategoryTarget> findCategory(long categoryId) {
			return Optional.ofNullable(categories.get(categoryId));
		}

		@Override
		public List<CategoryTarget> findPreparingCategoriesByMaterial(long materialId) {
			return categories.values().stream()
					.filter(c -> c.materialId() == materialId && c.isPreparing())
					.sorted(Comparator.comparingLong(CategoryTarget::id))
					.toList();
		}

		@Override
		public List<CategoryTarget> findPreparingCategoriesOfReadyMaterials() {
			return categories.values().stream()
					.filter(c -> c.isPreparing() && "READY".equals(c.materialStatus()))
					.sorted(Comparator.comparingLong(CategoryTarget::id))
					.toList();
		}

		@Override
		public List<AiPage> findPages(long materialId, int pageStart, int pageEnd) {
			return pages;
		}

		@Override
		public List<String> findRecentStems(long categoryId, int limit) {
			List<String> stems = new ArrayList<>();
			for (int i = questions.size() - 1; i >= 0 && stems.size() < limit; i--) {
				if (questions.get(i).categoryId() == categoryId) {
					stems.add(questions.get(i).stem());
				}
			}
			return stems;
		}

		@Override
		public int countQuestions(long categoryId, String type, int difficulty) {
			return (int) questions.stream()
					.filter(q -> q.categoryId() == categoryId && q.type().equals(type) && q.difficulty() == difficulty)
					.count();
		}

		@Override
		public void insertQuestions(long categoryId, String origin, List<GeneratedQuestion> generated) {
			generated.forEach(q -> questions.add(new StoredQuestion(categoryId, q.type(), q.difficulty(), q.stem(), origin)));
		}

		@Override
		public int markAvailable(long categoryId) {
			CategoryTarget c = categories.get(categoryId);
			if (c == null || !c.isPreparing()) {
				return 0;
			}
			categories.put(categoryId, new CategoryTarget(c.id(), c.materialId(), c.name(), c.pageStart(), c.pageEnd(),
					"AVAILABLE", c.materialStatus()));
			return 1;
		}
	}

	/** 받은 요청을 기록하고, 정해준 함수로 응답을 만든다. */
	static class FakeAiClient implements QuestionAiClient {

		final List<QuestionGenerateRequest> requests = new CopyOnWriteArrayList<>();
		Function<QuestionGenerateRequest, CompletableFuture<QuestionGenerateResponse>> behavior =
				request -> CompletableFuture.completedFuture(fulfill(request));

		@Override
		public CompletableFuture<QuestionGenerateResponse> generate(QuestionGenerateRequest request) {
			requests.add(request);
			return behavior.apply(request);
		}
	}

	private static int stemSeq = 0;

	/** 요청한 유형·개수 그대로 만든 응답(AI 서버 mock과 같은 동작). */
	static QuestionGenerateResponse fulfill(QuestionGenerateRequest request) {
		List<GeneratedQuestion> generated = new ArrayList<>();
		for (TypeRequest r : request.requests()) {
			for (int i = 0; i < r.count(); i++) {
				generated.add(question(r.type(), r.difficulty(), 2));
			}
		}
		return response(generated, List.of());
	}

	static GeneratedQuestion question(String type, int difficulty, int sourcePage) {
		return new GeneratedQuestion(type, difficulty, "[fake " + (++stemSeq) + "] " + type, List.of(), "O",
				List.of(), List.of(), "해설", sourcePage, "근거", List.of("태그"));
	}

	static QuestionGenerateResponse response(List<GeneratedQuestion> questions, List<Shortfall> shortfall) {
		return new QuestionGenerateResponse(questions, shortfall, new Rejected(0, 0, 0), null);
	}

	static int totalCount(QuestionGenerateRequest request) {
		return request.requests().stream().mapToInt(TypeRequest::count).sum();
	}

	static int countOf(QuestionGenerateRequest request, String type) {
		return request.requests().stream().filter(r -> r.type().equals(type)).mapToInt(TypeRequest::count).sum();
	}

	static int countOf(QuestionGenerateRequest request, String type, int difficulty) {
		return request.requests().stream()
				.filter(r -> r.type().equals(type) && r.difficulty() == difficulty)
				.mapToInt(TypeRequest::count)
				.sum();
	}
}
