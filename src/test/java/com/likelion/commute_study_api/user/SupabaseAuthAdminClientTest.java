package com.likelion.commute_study_api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.storage.StorageProperties;
import com.sun.net.httpserver.HttpServer;

class SupabaseAuthAdminClientTest {

	private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private HttpServer server;
	private final AtomicReference<String> method = new AtomicReference<>();
	private final AtomicReference<String> path = new AtomicReference<>();
	private final AtomicReference<String> apikey = new AtomicReference<>();
	private final AtomicReference<String> authorization = new AtomicReference<>();

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private void respond(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", exchange -> {
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getPath());
			apikey.set(exchange.getRequestHeaders().getFirst("apikey"));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(bytes);
				}
			}
			exchange.close();
		});
		server.start();
	}

	private SupabaseAuthAdminClient client(String key) {
		StorageProperties props = new StorageProperties();
		props.setUrl(server == null ? "http://localhost:1" : "http://localhost:" + server.getAddress().getPort());
		props.setKey(key);
		return new SupabaseAuthAdminClient(props);
	}

	private static void assertFailed(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(UserErrorCode.ACCOUNT_DELETE_FAILED));
	}

	@Test
	void 관리자_API로_계정을_지운다() throws IOException {
		respond(200, "{}");

		assertThat(client("sb_secret_test").deleteUser(USER)).isEqualTo(SupabaseAuthAdminClient.Result.DELETED);
		assertThat(method.get()).isEqualTo("DELETE");
		assertThat(path.get()).isEqualTo("/auth/v1/admin/users/" + USER);
		assertThat(apikey.get()).isEqualTo("sb_secret_test");
		assertThat(authorization.get()).isNull(); // sb_secret 키는 apikey만
	}

	@Test
	void 예전_service_role_키는_Authorization도_보낸다() throws IOException {
		respond(200, "{}");

		client("legacy.jwt.key").deleteUser(USER);
		assertThat(authorization.get()).isEqualTo("Bearer legacy.jwt.key");
	}

	@Test
	void 이미_없는_사용자는_성공으로_본다() throws IOException {
		respond(404, "{\"code\":404,\"error_code\":\"user_not_found\",\"msg\":\"User not found\"}");

		assertThat(client("sb_secret_test").deleteUser(USER))
				.isEqualTo(SupabaseAuthAdminClient.Result.ALREADY_DELETED);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"404|{\"message\":\"no route\"}", // 사용자 없음이 아닌 404(경로 오류 등)
			"404|not json",
			"401|{\"error_code\":\"not_admin\"}",
			"500|{}" })
	void 그_외_응답은_실패(int status, String body) throws IOException {
		respond(status, body);
		assertFailed(() -> client("sb_secret_test").deleteUser(USER));
	}

	@Test
	void 서버에_닿지_않으면_실패() {
		assertFailed(() -> client("sb_secret_test").deleteUser(USER)); // localhost:1, 연결 거부
	}

	@Test
	void 설정이_잘못되면_요청하지_않고_실패() throws IOException {
		respond(200, "{}");
		assertFailed(() -> client("").deleteUser(USER));
		assertFailed(() -> client("sb_publishable_x").deleteUser(USER));
		assertThat(method.get()).isNull();

		assertThat(SupabaseAuthAdminClient.configured("https://abc.supabase.co", "sb_secret_x")).isTrue();
		assertThat(SupabaseAuthAdminClient.configured("http://abc.supabase.co", "sb_secret_x")).isFalse();
		assertThat(SupabaseAuthAdminClient.configured("https://abc.supabase.co/x", "sb_secret_x")).isFalse();
		assertThat(SupabaseAuthAdminClient.configured("", "sb_secret_x")).isFalse();
	}
}
