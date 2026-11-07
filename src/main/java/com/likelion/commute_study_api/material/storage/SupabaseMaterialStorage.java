package com.likelion.commute_study_api.material.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.material.MaterialErrorCode;
import com.likelion.commute_study_api.material.MaterialUploadValidator.ValidatedFile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class SupabaseMaterialStorage implements MaterialStorage {
    private final StorageProperties properties;
    public SupabaseMaterialStorage(StorageProperties properties) { this.properties = properties; }

    public String reference(UUID userId, String extension) {
        configured();
        return properties.getBucket() + "/" + userId + "/" + UUID.randomUUID() + "." + extension;
    }

    public void upload(String reference, ValidatedFile file) {
        try {
            var client = client();
            Bucket bucket = client.get().uri("/bucket/" + properties.getBucket()).retrieve().body(Bucket.class);
            if (bucket == null || !Boolean.FALSE.equals(bucket.isPublic())) throw unavailable();
            client.post().uri("/object/" + safeReference(reference))
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .header("x-upsert", "false")
                    .body(file.file().getResource()).retrieve().toBodilessEntity();
        } catch (Exception e) { throw unavailable(); } // Never retain response bodies or URLs in an exception.
    }

    public String signedUrl(String reference, int ttlSeconds) {
        try {
            SignedUrl result = client().post().uri("/object/sign/" + safeReference(reference))
                    .body(Map.of("expiresIn", ttlSeconds)).retrieve().body(SignedUrl.class);
            if (result == null || result.signedURL() == null) throw unavailable();
            String raw = result.signedURL();
            URI base = URI.create(properties.getUrl());
            URI signed = raw.startsWith("/object/") ? URI.create(properties.getUrl() + "/storage/v1" + raw)
                    : base.resolve(raw);
            String expected = properties.getExpectedHost().isBlank() ? base.getHost() : properties.getExpectedHost();
            if (!base.getScheme().equals(signed.getScheme()) || !base.getHost().equals(signed.getHost())
                    || !expected.equalsIgnoreCase(signed.getHost()) || base.getPort() != signed.getPort()
                    || signed.getUserInfo() != null || signed.getFragment() != null
                    || !signed.getPath().equals("/storage/v1/object/sign/" + reference))
                throw unavailable();
            return signed.toASCIIString();
        } catch (Exception e) { throw unavailable(); }
    }

    public void delete(String reference) {
        try {
            client().delete().uri("/object/" + safeReference(reference)).retrieve().toBodilessEntity();
        } catch (Exception e) { throw unavailable(); }
    }

    private RestClient client() {
        configured();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        var builder = RestClient.builder().baseUrl(properties.getUrl() + "/storage/v1")
                .requestFactory(factory).defaultHeader("apikey", properties.getKey())
                .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> { throw unavailable(); });
        // Legacy service_role JWTs need Authorization; sb_secret keys are gateway API keys.
        if (!properties.getKey().startsWith("sb_secret_"))
            builder.defaultHeader("Authorization", "Bearer " + properties.getKey());
        return builder.build();
    }

    private void configured() {
        try {
            URI url = URI.create(properties.getUrl());
            boolean local = "http".equals(url.getScheme()) && ("localhost".equals(url.getHost()) || "127.0.0.1".equals(url.getHost()));
            if ((!local && !"https".equals(url.getScheme())) || url.getHost() == null
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || !url.getPath().isEmpty() || !properties.getBucket().matches("[A-Za-z0-9_-]+")
                    || properties.getKey().isBlank() || properties.getKey().startsWith("sb_publishable_"))
                throw unavailable();
        } catch (Exception e) { throw unavailable(); }
    }

    private String safeReference(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+/[a-f0-9-]+/[a-f0-9-]+\\.(pdf|pptx|png)"))
            throw unavailable();
        return value;
    }
    private ApiException unavailable() { return new ApiException(MaterialErrorCode.STORAGE_UNAVAILABLE); }
    record Bucket(@JsonProperty("public") Boolean isPublic) {}
    record SignedUrl(String signedURL) {}
}

