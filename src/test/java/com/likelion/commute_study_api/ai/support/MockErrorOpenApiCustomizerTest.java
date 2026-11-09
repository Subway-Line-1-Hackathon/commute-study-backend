package com.likelion.commute_study_api.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.common.config.AiProperties;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;

class MockErrorOpenApiCustomizerTest {

	private static OpenAPI sampleDocs() {
		Paths paths = new Paths();
		paths.addPathItem("/api/answers/judge", new PathItem().post(new Operation()));
		paths.addPathItem("/api/subjects", new PathItem().get(new Operation()).post(new Operation()));
		return new OpenAPI().paths(paths);
	}

	private static List<String> headerNames(Operation operation) {
		List<String> names = new ArrayList<>();
		if (operation.getParameters() != null) {
			for (Parameter p : operation.getParameters()) {
				names.add(p.getIn() + ":" + p.getName());
			}
		}
		return names;
	}

	private static MockErrorOpenApiCustomizer customizer(boolean passthrough) {
		AiProperties props = new AiProperties();
		props.setMockErrorPassthrough(passthrough);
		return new MockErrorOpenApiCustomizer(props);
	}

	@Test
	void 설정이_켜져_있으면_AI를_부르는_API에만_헤더_입력칸을_붙인다() {
		OpenAPI docs = sampleDocs();
		customizer(true).customise(docs);

		Operation judge = docs.getPaths().get("/api/answers/judge").getPost();
		assertThat(headerNames(judge)).containsExactly("header:X-Mock-Error");
		assertThat(judge.getParameters().get(0).getRequired()).isFalse();
		assertThat(judge.getParameters().get(0).getExample()).isNull(); // Swagger UI가 값을 미리 채우지 않게
		assertThat(judge.getParameters().get(0).getDescription()).contains("AI-03", "AI-02:LLM_FAILED");

		assertThat(headerNames(docs.getPaths().get("/api/subjects").getGet())).isEmpty();
		assertThat(headerNames(docs.getPaths().get("/api/subjects").getPost())).isEmpty();
	}

	@Test
	void 설정이_꺼져_있으면_아무것도_붙이지_않는다() {
		OpenAPI docs = sampleDocs();
		customizer(false).customise(docs);

		assertThat(headerNames(docs.getPaths().get("/api/answers/judge").getPost())).isEmpty();
	}

	@Test
	void 두_번_적용해도_한_번만_붙는다() {
		OpenAPI docs = sampleDocs();
		customizer(true).customise(docs);
		customizer(true).customise(docs);

		assertThat(headerNames(docs.getPaths().get("/api/answers/judge").getPost())).hasSize(1);
	}

	/** 실제로 생성되는 Swagger 문서에서 대상 경로가 모두 존재하고 입력칸이 붙는지 확인(경로 이름이 바뀌면 실패). */
	@Nested
	@SpringBootTest(properties = {
			"ai.mock-error-passthrough=true",
			"spring.config.import=", "SUPABASE_URL=https://auth.example.invalid", "material.cleanup-enabled=false",
			"spring.datasource.url=jdbc:h2:mem:mock-error-docs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
			"spring.datasource.username=sa", "spring.datasource.password=",
			"spring.datasource.driver-class-name=org.h2.Driver",
			"spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false",
			"spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration" })
	@AutoConfigureMockMvc
	class 실제_문서 {

		@Autowired
		MockMvc mvc;
		@MockitoBean
		JwtDecoder decoder;

		@Test
		void 대상_API_7개에만_입력칸이_붙는다() throws Exception {
			String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
					.getContentAsString(StandardCharsets.UTF_8);
			JsonNode paths = new ObjectMapper().readTree(json).get("paths");

			List<String> withHeader = new ArrayList<>();
			paths.properties().forEach(path -> path.getValue().properties().forEach(op -> {
				JsonNode params = op.getValue().get("parameters");
				if (params != null) {
					params.forEach(p -> {
						if ("X-Mock-Error".equals(p.path("name").asText()) && "header".equals(p.path("in").asText())) {
							withHeader.add(op.getKey().toUpperCase() + " " + path.getKey());
						}
					});
				}
			}));

			assertThat(withHeader).containsExactlyInAnyOrderElementsOf(MockErrorOpenApiCustomizer.TARGETS.keySet());
		}
	}
}
