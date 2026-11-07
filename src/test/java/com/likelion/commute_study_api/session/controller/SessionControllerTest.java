package com.likelion.commute_study_api.session.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.SessionErrorCode;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.SessionQuestion;
import com.likelion.commute_study_api.session.service.SessionService;

@SpringBootTest(properties = {
		"spring.config.import=",
		"SUPABASE_URL=https://auth.example.invalid",
		"SUPABASE_PUBLISHABLE_KEY=sb_publishable_test",
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class SessionControllerTest {

	private static final String VALID_BODY = """
			{"source":"categories","categoryIds":[7],"questionTypes":["MC","SHORT"],"difficulty":2}
			""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	SessionService sessionService;

	@MockitoBean
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void 토큰이_없으면_401() throws Exception {
		mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		verify(sessionService, never()).create(any(), any());
	}

	@Test
	void 만들면_201과_세션을_돌려준다() throws Exception {
		when(sessionService.create(any(), any())).thenReturn(new CreateSessionResponse(12L, StudyMode.CURRICULUM, 20, 2,
				"요약", null, null, List.of(
						SessionQuestion.of(101, "MC", "큐는?", List.of("LIFO", "FIFO"), 2, "FIFO", "해설"),
						SessionQuestion.of(102, "SHORT", "스택 삽입은?", List.of(), 1, "push", "해설"))));

		mvc.perform(post("/api/sessions").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sessionId").value(12))
				.andExpect(jsonPath("$.mode").value("CURRICULUM"))
				.andExpect(jsonPath("$.questionCount").value(2))
				.andExpect(jsonPath("$.shortage").doesNotExist())
				.andExpect(jsonPath("$.noReviewItems").doesNotExist())
				.andExpect(jsonPath("$.questions[0].answer").value("FIFO"))
				.andExpect(jsonPath("$.questions[0].choices[1]").value("FIFO"))
				.andExpect(jsonPath("$.questions[1].answer").doesNotExist())
				.andExpect(jsonPath("$.questions[1].explanation").doesNotExist());
	}

	@Test
	void 범위를_벗어난_값은_400_VALIDATION_ERROR() throws Exception {
		mvc.perform(post("/api/sessions").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
						{"source":"categories","categoryIds":[7],"questionTypes":["ESSAY"],"difficulty":4}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details.difficulty").exists());
		verify(sessionService, never()).create(any(), any());
	}

	@Test
	void 단원_선택인데_단원이_없으면_400() throws Exception {
		mvc.perform(post("/api/sessions").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
						{"source":"categories","questionTypes":["MC"],"difficulty":2}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.categoryIdsValid").exists());
	}

	@Test
	void 모르는_source는_400() throws Exception {
		mvc.perform(post("/api/sessions").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
						{"source":"wrong_notes","questionTypes":["MC"],"difficulty":2}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 서비스의_도메인_에러는_422와_코드로() throws Exception {
		when(sessionService.create(any(), any())).thenThrow(new ApiException(SessionErrorCode.SETUP_REQUIRED));

		mvc.perform(post("/api/sessions").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
	}

	@Test
	void Swagger_문서에_나온다() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/sessions'].post.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/sessions'].post.responses['201']").exists())
				.andExpect(jsonPath("$.paths['/api/sessions'].post.responses['422']").exists())
				.andExpect(jsonPath("$.paths['/api/sessions'].post.requestBody.content['application/json'].examples['단원 선택']").exists());
	}
}
