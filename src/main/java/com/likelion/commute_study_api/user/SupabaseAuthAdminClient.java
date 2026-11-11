package com.likelion.commute_study_api.user;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.storage.StorageProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Supabase Auth 관리자 API로 로그인 계정(auth.users)을 지운다(#67 회원 탈퇴).
 * auth.users가 지워지면 app_user부터 모든 사용자 데이터가 FK on delete cascade로 함께 지워진다.
 *
 * <p>Storage와 같은 SUPABASE_URL, secret key(SUPABASE_STORAGE_KEY)를 쓴다. 새 환경변수는 없다.
 * 키나 응답 본문은 로그·예외에 남기지 않는다.
 */
@Component
public class SupabaseAuthAdminClient {

	private static final Logger log = LoggerFactory.getLogger(SupabaseAuthAdminClient.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** DELETED = 이번에 지움, ALREADY_DELETED = 이미 없음(재시도 등). 둘 다 성공으로 본다. */
	public enum Result { DELETED, ALREADY_DELETED }

	private final StorageProperties properties;

	public SupabaseAuthAdminClient(StorageProperties properties) {
		this.properties = properties;
	}

	public Result deleteUser(UUID userId) {
		RestClient client = client();
		try {
			return client.delete().uri("/auth/v1/admin/users/{id}", userId).exchange((request, response) -> {
				int status = response.getStatusCode().value();
				if (response.getStatusCode().is2xxSuccessful()) {
					return Result.DELETED;
				}
				if (status == 404 && isUserNotFound(response.getBody())) {
					return Result.ALREADY_DELETED;
				}
				log.error("[회원] 인증 계정 삭제 실패 userId={} status={}", userId, status);
				throw failed();
			});
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			log.error("[회원] 인증 계정 삭제 요청 실패 userId={} error={}", userId, e.getClass().getSimpleName());
			throw failed();
		}
	}

	/** 404여도 '사용자 없음'일 때만 성공으로 본다. 경로가 틀린 404 등을 성공으로 오해하지 않게. */
	private static boolean isUserNotFound(InputStream body) {
		try {
			JsonNode node = MAPPER.readTree(body);
			JsonNode code = node == null ? null : node.get("error_code");
			return code != null && code.isString() && "user_not_found".equals(code.stringValue());
		} catch (Exception e) {
			return false;
		}
	}

	private RestClient client() {
		String key = properties.getKey();
		if (!configured(properties.getUrl(), key)) {
			log.error("[회원] Supabase 관리자 설정 누락(SUPABASE_URL 또는 secret key)");
			throw failed();
		}
		var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build());
		factory.setReadTimeout(Duration.ofSeconds(10));
		var builder = RestClient.builder().baseUrl(properties.getUrl()).requestFactory(factory)
				.defaultHeader("apikey", key);
		// 예전 service_role JWT는 Authorization도 필요하다. sb_secret 키는 게이트웨이용 API 키라 apikey만 보낸다.
		if (!key.startsWith("sb_secret_")) {
			builder.defaultHeader("Authorization", "Bearer " + key);
		}
		return builder.build();
	}

	/** SupabaseMaterialStorage와 같은 기준: https(로컬만 http), 경로·쿼리 없음, 공개 키 금지. */
	static boolean configured(String url, String key) {
		try {
			if (url == null || key == null || key.isBlank() || key.startsWith("sb_publishable_")) {
				return false;
			}
			URI uri = URI.create(url);
			boolean local = "http".equals(uri.getScheme())
					&& ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
			return (local || "https".equals(uri.getScheme())) && uri.getHost() != null
					&& uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
					&& uri.getPath().isEmpty();
		} catch (Exception e) {
			return false;
		}
	}

	private static ApiException failed() {
		return new ApiException(UserErrorCode.ACCOUNT_DELETE_FAILED);
	}
}
