package com.likelion.commute_study_api.ai.support;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.common.config.AiProperties;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;

/**
 * ai.mock-error-passthrough가 켜진 서버의 Swagger에만, AI를 일으키는 API에 X-Mock-Error 입력칸을 붙인다(#36 실패 경로 시험).
 * 운영(설정 꺼짐)의 Swagger에는 나타나지 않는다. 컨트롤러마다 어노테이션을 달지 않고 경로 목록으로 관리한다.
 */
@Component
public class MockErrorOpenApiCustomizer implements OpenApiCustomizer {

	/** "메서드 경로" → 그 API가 부르는 AI 기능 설명. */
	static final Map<String, String> TARGETS = new LinkedHashMap<>();

	static {
		TARGETS.put("POST /api/subjects/{id}/materials",
				"AI-01 단원화 → 성공하면 AI-02 사전 생성(둘 다 비동기). 결과는 `GET /api/materials/{id}/status`와 단원 목록으로 확인");
		TARGETS.put("POST /api/materials/{id}/retry", "AI-01 단원화 → 성공하면 AI-02 사전 생성(둘 다 비동기)");
		TARGETS.put("POST /api/sessions", "AI-02 추가 생성(있는 문제가 모자랄 때만)");
		TARGETS.put("POST /api/sessions/{id}/end", "AI-06 통학 후 피드백(비동기, `NEW_START` 제외)");
		TARGETS.put("POST /api/attempts", "AI-06 통학 후 피드백(마지막 답으로 COMPLETED가 될 때만, 비동기)");
		TARGETS.put("POST /api/answers/judge", "AI-03 단답 판정(정답 목록과 일치하지 않는 답일 때만)");
		TARGETS.put("POST /api/questions/{id}/ask", "AI-05 AI 질문(SSE)");
	}

	private static final String COMMON = """
			**개발·데모용. 평소에는 비워 둔다.** AI 서버가 mock(`AI_MOCK=true`)일 때 이 값의 에러를 흉내 낸다.
			- 형식: `LLM_TIMEOUT`(이 요청이 일으키는 모든 AI 호출에 적용) 또는 `AI-02:LLM_FAILED`(그 기능에만 적용)
			- 코드: `INVALID_REQUEST`, `UNAUTHORIZED`, `LLM_FAILED`, `LLM_TIMEOUT`,
			  자료(AI-01) 전용 `UNSUPPORTED_FILE`, `PAGE_LIMIT_EXCEEDED`, `TEXT_EXTRACTION_FAILED`, `FILE_FETCH_FAILED`
			""";

	private final AiProperties aiProperties;

	public MockErrorOpenApiCustomizer(AiProperties aiProperties) {
		this.aiProperties = aiProperties;
	}

	@Override
	public void customise(OpenAPI openApi) {
		if (!aiProperties.isMockErrorPassthrough() || openApi.getPaths() == null) {
			return;
		}
		TARGETS.forEach((key, features) -> {
			String[] methodAndPath = key.split(" ", 2);
			PathItem item = openApi.getPaths().get(methodAndPath[1]);
			if (item == null) {
				return;
			}
			Operation operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(methodAndPath[0]));
			if (operation == null || hasMockErrorParameter(operation)) {
				return;
			}
			// example을 두지 않는다: Swagger UI가 Try it out에서 값을 미리 채워 보내기 때문(#29)
			operation.addParametersItem(new HeaderParameter()
					.name(MockErrorContext.HEADER)
					.required(false)
					.schema(new StringSchema())
					.description("이 API가 부르는 AI: " + features + "\n\n" + COMMON));
		});
	}

	private static boolean hasMockErrorParameter(Operation operation) {
		return operation.getParameters() != null && operation.getParameters().stream()
				.anyMatch(p -> MockErrorContext.HEADER.equalsIgnoreCase(p.getName()));
	}
}