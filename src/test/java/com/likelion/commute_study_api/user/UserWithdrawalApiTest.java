package com.likelion.commute_study_api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.MaterialErrorCode;
import com.likelion.commute_study_api.material.storage.MaterialStorage;

import tools.jackson.databind.ObjectMapper;

/** DELETE /api/users/me (#67). Storage와 Supabase 관리자 API는 가짜로 바꾼다. */
@SpringBootTest(properties = {
		"spring.config.import=", "SUPABASE_URL=https://auth.example.invalid", "material.cleanup-enabled=false",
		"spring.datasource.url=jdbc:h2:mem:user-withdrawal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa", "spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false", "spring.data.jpa.repositories.enabled=false", "spring.sql.init.mode=always",
		"spring.sql.init.schema-locations=classpath:material-test-schema.sql,classpath:db/migration/V4__add_material_file.sql",
		"spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration" })
@AutoConfigureMockMvc
class UserWithdrawalApiTest {

	private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	MaterialStorage storage;
	@MockitoBean
	SupabaseAuthAdminClient authAdmin;
	@MockitoBean
	JwtDecoder decoder;

	@BeforeEach
	void reset() {
		for (String table : List.of("question", "category", "material_page", "material_file", "material", "subject",
				"app_user")) {
			jdbc.update("DELETE FROM " + table);
		}
		when(authAdmin.deleteUser(any())).thenReturn(SupabaseAuthAdminClient.Result.DELETED);
	}

	private static MockHttpServletRequestBuilder withdraw(UUID user) {
		return delete("/api/users/me").with(jwt().jwt(j -> j.subject(user.toString())));
	}

	private void material(UUID user, String url) {
		jdbc.update("INSERT INTO app_user(id) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE id = ?)", user, user);
		jdbc.update("INSERT INTO subject(user_id, name) VALUES (?, '과목')", user);
		long subject = jdbc.queryForObject("SELECT max(id) FROM subject", Long.class);
		jdbc.update("INSERT INTO material(subject_id, file_url, file_type, status) VALUES (?, ?, 'PDF', 'READY')",
				subject, url);
	}

	@Test
	void 파일을_먼저_지우고_계정을_지운_뒤_204() throws Exception {
		material(USER, "b/u/a.pdf");
		material(USER, "b/u/b.pdf");
		material(OTHER, "b/o/x.pdf");

		mvc.perform(withdraw(USER)).andExpect(status().isNoContent()).andExpect(content().string(""));

		var order = inOrder(storage, authAdmin);
		order.verify(storage).delete("b/u/a.pdf");
		order.verify(storage).delete("b/u/b.pdf");
		order.verify(authAdmin).deleteUser(USER);
		verify(storage, never()).delete("b/o/x.pdf"); // 다른 사용자 파일은 건드리지 않는다
		verify(authAdmin, never()).deleteUser(OTHER);
	}

	@Test
	void 파일_삭제가_일부_실패해도_탈퇴는_진행한다() throws Exception {
		material(USER, "b/u/a.pdf");
		doThrow(new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE)).when(storage).delete("b/u/a.pdf");

		mvc.perform(withdraw(USER)).andExpect(status().isNoContent());
		verify(authAdmin).deleteUser(USER);
	}

	@Test
	void 계정_삭제에_실패하면_502이고_다시_시도할_수_있다() throws Exception {
		material(USER, "b/u/a.pdf");
		when(authAdmin.deleteUser(USER)).thenThrow(new ApiException(UserErrorCode.ACCOUNT_DELETE_FAILED));

		mvc.perform(withdraw(USER)).andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DELETE_FAILED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, USER)).isOne();

		// 다시 시도: 그사이 계정이 지워졌어도 성공. (예외를 던지게 해 둔 메서드라 doReturn으로 바꾼다)
		org.mockito.Mockito.doReturn(SupabaseAuthAdminClient.Result.ALREADY_DELETED).when(authAdmin).deleteUser(USER);
		mvc.perform(withdraw(USER)).andExpect(status().isNoContent());
	}

	@Test
	void 회원_행이_없어도_새로_만들지_않고_탈퇴한다() throws Exception {
		mvc.perform(withdraw(USER)).andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isZero();
		verify(storage, never()).delete(anyString());
		verify(authAdmin).deleteUser(USER);
	}

	@Test
	void 토큰이_없거나_잘못되면_401이고_아무것도_지우지_않는다() throws Exception {
		material(USER, "b/u/a.pdf");

		mvc.perform(delete("/api/users/me")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/users/me").with(jwt().jwt(j -> j.subject("not-a-uuid"))))
				.andExpect(status().isUnauthorized());

		verify(storage, never()).delete(anyString());
		verify(authAdmin, never()).deleteUser(any());
	}

	@Test
	void Swagger에_204와_502가_문서화된다() throws Exception {
		String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		var responses = new ObjectMapper().readTree(json).path("paths").path("/api/users/me").path("delete")
				.path("responses");

		assertThat(responses.has("204")).isTrue();
		assertThat(responses.has("401")).isTrue();
		assertThat(responses.has("502")).isTrue();
	}
}
