package com.likelion.commute_study_api.controller;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwaggerAuthControllerTest {
    private String legacyKey(String role) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"role\":\"" + role + "\"}").getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void exposesOnlyPublishableOrLegacyAnonKeys() {
        assertTrue(SwaggerAuthController.isPublicKey("sb_publishable_test"));
        assertTrue(SwaggerAuthController.isPublicKey(legacyKey("anon")));
        for (String key : new String[] { "sb_secret_test", legacyKey("service_role"), "jwt-secret", "" }) {
            var response = new SwaggerAuthController("https://test.supabase.co", key).config();
            assertEquals(false, response.getBody().get("enabled"));
            assertFalse(response.getBody().containsKey("publishableKey"));
            assertEquals("no-store", response.getHeaders().getCacheControl());
        }
    }

    @Test
    void unsafeUrlDisablesGoogleLoginWithoutExposingKey() {
        var response = new SwaggerAuthController("http://external.example", "sb_publishable_test").config();
        assertEquals(false, response.getBody().get("enabled"));
        assertFalse(response.getBody().containsKey("publishableKey"));
    }
}
