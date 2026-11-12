package com.likelion.commute_study_api.answer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.likelion.commute_study_api.answer.dto.JudgeAnswerResponse;
import com.likelion.commute_study_api.answer.service.AnswerJudgeService;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

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
class AnswerJudgeControllerTest {

	private static final String VALID_BODY = """
			{"questionId":103,"answer":"대기열"}
			""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	AnswerJudgeService answerJudgeService;

	@MockitoBean
	JdbcTemplate jdbcTemplate;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void 토큰이_없으면_401() throws Exception {
		mvc.perform(post("/api/answers/judge").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnauthorized());
		verify(answerJudgeService, never()).judge(any(), any());
	}

	@Test
	void AI_판정은_200과_correct_reason() throws Exception {
		when(answerJudgeService.judge(any(), any())).thenReturn(JudgeAnswerResponse.judged(true, "같은 뜻이에요.", "대기"));

		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.correct").value(true))
				.andExpect(jsonPath("$.reason").value("같은 뜻이에요."))
				.andExpect(jsonPath("$.answer").value("대기"))
				.andExpect(jsonPath("$.timeout").doesNotExist());
		verify(answerJudgeService).judge(any(), argThat(r -> r.questionId() == 103L && r.answer().equals("대기열")));
	}

	@Test
	void 정답_목록과_일치하면_200과_correct_answer() throws Exception {
		when(answerJudgeService.judge(any(), any())).thenReturn(JudgeAnswerResponse.matched("대기열"));

		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.correct").value(true))
				.andExpect(jsonPath("$.answer").value("대기열"))
				.andExpect(jsonPath("$.reason").doesNotExist());
	}

	@Test
	void AI_실패도_200이고_correct는_null로_내려가지만_answer는_내려간다() throws Exception {
		when(answerJudgeService.judge(any(), any())).thenReturn(JudgeAnswerResponse.timedOut("대기열"));

		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.correct").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.correct").hasJsonPath())
				.andExpect(jsonPath("$.timeout").value(true))
				.andExpect(jsonPath("$.answer").value("대기열"))
				.andExpect(jsonPath("$.reason").doesNotExist());
	}

	@Test
	void 답이_30자를_넘거나_필수값이_없으면_400() throws Exception {
		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"questionId\":103,\"answer\":\"" + "가".repeat(31) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.details.answer").exists());
		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"answer\":\"큐\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.questionId").exists());
		verify(answerJudgeService, never()).judge(any(), any());
	}

	@Test
	void 남의_문제는_404() throws Exception {
		when(answerJudgeService.judge(any(), any()))
				.thenThrow(new ApiException(CommonErrorCode.NOT_FOUND, "문제를 찾을 수 없습니다."));

		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 단답이_아닌_문제는_400() throws Exception {
		when(answerJudgeService.judge(any(), any())).thenThrow(new ApiException(CommonErrorCode.VALIDATION_ERROR,
				"단답 문제만 판정할 수 있습니다.", Map.of("questionId", "단답(SHORT) 문제가 아님: MC")));

		mvc.perform(post("/api/answers/judge").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.details.questionId").exists());
	}

	@Test
	void Swagger_문서에_나온다() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/answers/judge'].post.security[0].bearerAuth").isArray())
				.andExpect(jsonPath("$.paths['/api/answers/judge'].post.responses['200'].content['application/json'].examples['AI 실패·시간 초과(자가 판정)']").exists())
				.andExpect(jsonPath("$.paths['/api/answers/judge'].post.responses['404']").exists());
	}
}
