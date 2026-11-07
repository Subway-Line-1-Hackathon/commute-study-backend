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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.SaveAttemptsRequest;
import com.likelion.commute_study_api.session.dto.SaveAttemptsResponse;
import com.likelion.commute_study_api.session.service.AttemptService;

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
class AttemptControllerTest {

	private static final String VALID_BODY = """
			{"sessionId":12,"attempts":[
			  {"questionId":101,"answer":"2","answeredAt":"2026-11-08T08:05:00+09:00"},
			  {"questionId":102,"answer":"push","correct":true,"selfJudged":true,"answeredAt":"2026-11-08T08:06:30+09:00"}
			]}
			""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AttemptService attemptService;

	@MockitoBean
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void 토큰이_없으면_401() throws Exception {
		mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		verify(attemptService, never()).save(any(), any());
	}

	@Test
	void 저장하면_200과_진행_상황() throws Exception {
		when(attemptService.save(any(), any())).thenReturn(
				new SaveAttemptsResponse(2, new SaveAttemptsResponse.Progress(9, 27), SessionStatus.IN_PROGRESS));

		mvc.perform(post("/api/attempts").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.saved").value(2))
				.andExpect(jsonPath("$.progress.answered").value(9))
				.andExpect(jsonPath("$.progress.total").value(27))
				.andExpect(jsonPath("$.sessionStatus").value("IN_PROGRESS"));

		ArgumentCaptor<SaveAttemptsRequest> captor = ArgumentCaptor.forClass(SaveAttemptsRequest.class);
		verify(attemptService).save(any(), captor.capture());
		var items = captor.getValue().attempts();
		org.junit.jupiter.api.Assertions.assertEquals(Instant.parse("2026-11-07T23:05:00Z"), items.get(0).answeredAt());
		org.junit.jupiter.api.Assertions.assertNull(items.get(0).correct());
		org.junit.jupiter.api.Assertions.assertTrue(items.get(1).selfJudgedOrFalse());
	}

	@Test
	void 필수값이_없거나_비어_있으면_400() throws Exception {
		mvc.perform(post("/api/attempts").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
						{"sessionId":12,"attempts":[]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(post("/api/attempts").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""
						{"sessionId":12,"attempts":[{"questionId":101,"answer":"2"}]}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verify(attemptService, never()).save(any(), any());
	}

	@Test
	void 세션에_없는_문제는_404와_문제_id() throws Exception {
		when(attemptService.save(any(), any())).thenThrow(new ApiException(CommonErrorCode.NOT_FOUND,
				"세션에 없는 문제가 포함되어 있습니다.", Map.of("questionIds", List.of(999L))));

		mvc.perform(post("/api/attempts").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.details.questionIds[0]").value(999));
	}

	@Test
	void 출발_전_세션은_409() throws Exception {
		when(attemptService.save(any(), any())).thenThrow(new ApiException(CommonErrorCode.STATE_CONFLICT,
				"출발하지 않은 세션에는 답안을 저장할 수 없습니다.", Map.of("status", "READY")));

		mvc.perform(post("/api/attempts").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
	}

	@Test
	void Swagger_문서에_나온다() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/attempts'].post.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/attempts'].post.responses['200']").exists())
				.andExpect(jsonPath("$.paths['/api/attempts'].post.responses['409']").exists());
	}
}
