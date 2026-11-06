package com.likelion.commute_study_api.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Public documentation assets only; API authentication remains in SecurityConfig. */
@Hidden
@RestController
public class SwaggerAuthController {
    private final String url;
    private final String publicKey;

    public SwaggerAuthController(@Value("${SUPABASE_URL:}") String url,
            @Value("${SUPABASE_PUBLISHABLE_KEY:${SUPABASE_ANON_KEY:}}") String publicKey) {
        this.url = url;
        this.publicKey = publicKey;
    }

    @GetMapping(value = "/swagger-ui/auth-config", produces = "application/json")
    public ResponseEntity<Map<String, Object>> config() {
        // Fail closed: never serialize arbitrary configured credentials to a browser.
        if (!safeUrl(url) || !isPublicKey(publicKey)) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("enabled", false));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("enabled", true, "url", url, "publishableKey", publicKey));
    }

    static boolean isPublicKey(String key) {
        if (key.matches("sb_publishable_[A-Za-z0-9_-]+")) {
            return true;
        }
        // Legacy anon keys are public JWTs. This is an accidental-secret guard,
        // not cryptographic verification or API authorization.
        try {
            String[] parts = key.split("\\.");
            if (parts.length != 3) return false;
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return "anon".equals(new ObjectMapper().readTree(payload).path("role").asString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean safeUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @GetMapping(value = "/swagger-ui/index.html", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> index() {
        return asset("index.html");
    }

    @GetMapping(value = "/swagger-ui/google-auth.js", produces = "text/javascript;charset=UTF-8")
    public ResponseEntity<Resource> script() {
        return asset("google-auth.js");
    }

    @GetMapping(value = "/swagger-ui/supabase.js", produces = "text/javascript")
    public ResponseEntity<Resource> sdk() {
        return asset("supabase.js");
    }

    private ResponseEntity<Resource> asset(String name) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(new ClassPathResource("swagger-auth/" + name));
    }
}
