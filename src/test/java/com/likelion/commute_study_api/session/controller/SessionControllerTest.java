package com.likelion.commute_study_api.session.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.likelion.commute_study_api.common.exception.CommonErrorCode;
import com.likelion.commute_study_api.common.study.StudyMode;
import com.likelion.commute_study_api.session.EndReason;
import com.likelion.commute_study_api.session.SessionErrorCode;
import com.likelion.commute_study_api.session.SessionStatus;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse;
import com.likelion.commute_study_api.session.dto.CreateSessionResponse.SessionQuestion;
import com.likelion.commute_study_api.session.dto.CurrentSessionResponse;
import com.likelion.commute_study_api.session.dto.EndSessionResponse;
import com.likelion.commute_study_api.session.dto.StartSessionResponse;
import com.likelion.commute_study_api.session.service.SessionProgressService;
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
	SessionProgressService progressService;

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
				.andExpect(jsonPath("$.paths['/api/sessions'].post.requestBody.content['application/json'].examples['단원 선택']").exists())
				.andExpect(jsonPath("$.paths['/api/sessions/current'].get.responses['204']").exists())
				.andExpect(jsonPath("$.paths['/api/sessions/{id}/start'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/sessions/{id}/end'].post.security[0].bearerAuth").isArray());
	}

	// ---- 시작·종료·이어하기 ----

	@Test
	void 진행_중_세션이_있으면_200() throws Exception {
		when(progressService.current(any()))
				.thenReturn(Optional.of(new CurrentSessionResponse(12, List.of("스택", "큐"), 7, 27)));

		mvc.perform(get("/api/sessions/current").with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sessionId").value(12))
				.andExpect(jsonPath("$.categoryNames[1]").value("큐"))
				.andExpect(jsonPath("$.answered").value(7))
				.andExpect(jsonPath("$.total").value(27));
	}

	@Test
	void 진행_중_세션이_없으면_204() throws Exception {
		when(progressService.current(any())).thenReturn(Optional.empty());

		mvc.perform(get("/api/sessions/current").with(jwt()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
	}

	@Test
	void 시작하면_200과_시작_시각() throws Exception {
		when(progressService.start(any(), eq(12L)))
				.thenReturn(new StartSessionResponse(SessionStatus.IN_PROGRESS, Instant.parse("2026-11-08T01:00:00Z")));

		mvc.perform(post("/api/sessions/12/start").with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.startedAt").value("2026-11-08T01:00:00Z"));
	}

	@Test
	void 시작_상태_충돌은_409() throws Exception {
		when(progressService.start(any(), eq(12L))).thenThrow(new ApiException(CommonErrorCode.STATE_CONFLICT,
				"READY 세션만 시작할 수 있습니다.", Map.of("status", "COMPLETED")));

		mvc.perform(post("/api/sessions/12/start").with(jwt()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
				.andExpect(jsonPath("$.details.status").value("COMPLETED"));
	}

	@Test
	void 종료하면_200과_ENDED() throws Exception {
		when(progressService.end(any(), eq(12L), eq(EndReason.ARRIVED)))
				.thenReturn(new EndSessionResponse(SessionStatus.ENDED));

		mvc.perform(post("/api/sessions/12/end").with(jwt()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"ARRIVED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ENDED"));
	}

	@Test
	void 종료_사유가_없거나_모르는_값이면_400() throws Exception {
		mvc.perform(post("/api/sessions/12/end").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(post("/api/sessions/12/end").with(jwt()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"BYE\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verify(progressService, never()).end(any(), anyLong(), any());
	}

	@Test
	void 시작_종료_이어하기도_토큰이_없으면_401() throws Exception {
		mvc.perform(get("/api/sessions/current")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/sessions/12/start")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/sessions/12/end").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"QUIT\"}"))
				.andExpect(status().isUnauthorized());
	}
}
