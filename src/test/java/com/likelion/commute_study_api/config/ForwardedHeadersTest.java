package com.likelion.commute_study_api.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// A real embedded server exercises Boot's filter registration/order before Security/CORS.
// Do not override forward-headers-strategy here: the production configuration must enable it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "SUPABASE_URL=https://auth.example.invalid",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class ForwardedHeadersTest {
    private static final String EXTERNAL = "https://commute-study-backend-production.up.railway.app";
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    int port;

    @MockitoBean
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    JwtDecoder jwtDecoder;

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private HttpRequest.Builder proxied(String path) {
        return request(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "commute-study-backend-production.up.railway.app")
                .header("X-Forwarded-Port", "443");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String server(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("servers").get(0).path("url").asString();
    }

    @Test
    void serversSwitchBetweenLocalHttpAndProxyHttpsWithoutCachedHttpLeaking() throws Exception {
        String local = "http://localhost:" + port;
        assertThat(server(send(request("/v3/api-docs"))) + "/api/auth/me").isEqualTo(local + "/api/auth/me");
        assertThat(server(send(proxied("/v3/api-docs"))) + "/api/auth/me").isEqualTo(EXTERNAL + "/api/auth/me");
        assertThat(server(send(request("/v3/api-docs")))).isEqualTo(local);
    }

    @Test
    void standardForwardedHeaderAndDifferentDeploymentHostWorkWithoutHardcoding() throws Exception {
        var response = send(request("/v3/api-docs")
                .header("Forwarded", "for=192.0.2.1;proto=https;host=preview.example.com"));
        assertThat(server(response)).isEqualTo("https://preview.example.com");
    }

    @Test
    void swaggerRedirectAndConfigHonorExternalHttps() throws Exception {
        var redirect = send(proxied("/swagger-ui.html"));
        assertThat(redirect.statusCode()).isBetween(300, 399);
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertThat(URI.create(EXTERNAL).resolve(location).toString())
                .isEqualTo(EXTERNAL + "/swagger-ui/index.html");
        var config = send(proxied("/v3/api-docs/swagger-config"));
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(json.readTree(config.body()).path("oauth2RedirectUrl").asString())
                .isEqualTo(EXTERNAL + "/swagger-ui/oauth2-redirect.html");
    }

    @Test
    void sameOriginHttpsIsNotMisclassifiedAsCorsAndBearerStillAuthenticates() throws Exception {
        var jwt = Jwt.withTokenValue("proxy-test-token").header("alg", "ES256")
                .subject("11111111-1111-1111-1111-111111111111").claim("email", "test@example.invalid").build();
        when(jwtDecoder.decode("proxy-test-token")).thenReturn(jwt);
        // The same public origin must not require inclusion in the cross-origin allowlist.
        var response = send(proxied("/api/auth/me").header("Origin", EXTERNAL)
                .header("Authorization", "Bearer proxy-test-token"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("userId").asString()).isEqualTo(jwt.getSubject());
        assertThat(send(proxied("/api/auth/me").header("Origin", EXTERNAL)).statusCode()).isEqualTo(401);
        assertThat(send(proxied("/api/users/me/nickname").header("Origin", EXTERNAL)
                .header("Authorization", "Bearer proxy-test-token")).statusCode()).isEqualTo(200);
        // JdbcTemplate is a mock: this cannot update a real nickname/database.
        assertThat(send(proxied("/api/users/me/nickname").header("Origin", EXTERNAL)
                .header("Authorization", "Bearer proxy-test-token").header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"nickname\":\"동현\"}"))).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" })
    void allowedFrontendPreflightStillWorksBehindHttpsProxy(String method) throws Exception {
        var response = send(proxied("/api/auth/me").header("Origin", "http://localhost:5500")
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5500");
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow()).contains(method);
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow().toLowerCase())
                .contains("authorization", "content-type");
    }

    @Test
    void unlistedOriginRemainsRejected() throws Exception {
        var response = send(proxied("/api/auth/me").header("Origin", "https://unlisted.example.invalid")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}
