package com.likelion.commute_study_api.material.storage;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import com.likelion.commute_study_api.material.MaterialUploadValidator;
import com.likelion.commute_study_api.common.exception.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class SupabaseMaterialStorageTest {
    HttpServer server;
    StorageProperties properties;
    SupabaseMaterialStorage storage;
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    boolean publicBucket;
    boolean redirect;
    String signedOverride;
    int deleteStatus = 200;
    String deleteBody = "{}";
    volatile String authorization;
    volatile String apiKey;
    volatile String signBody;
    volatile byte[] uploaded;

    @BeforeEach
    void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            String path=exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod()+" "+path);
            authorization=exchange.getRequestHeaders().getFirst("Authorization");
            apiKey=exchange.getRequestHeaders().getFirst("apikey");
            int status=200; String body="{}";
            if(exchange.getRequestMethod().equals("DELETE")) { status=deleteStatus; body=deleteBody; }
            else if(path.contains("/bucket/")) body="{\"public\":"+publicBucket+"}";
            else if(path.contains("/object/sign/")) {
                signBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                String signed=signedOverride==null?path.substring("/storage/v1".length())+"?token=SECRET":signedOverride;
                body="{\"signedURL\":\""+signed+"\"}";
            } else if(exchange.getRequestMethod().equals("POST")) {
                uploaded=exchange.getRequestBody().readAllBytes();
                if(redirect) { status=307; exchange.getResponseHeaders().set("Location","/redirect-target"); }
            }
            exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] response=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        properties=new StorageProperties(); properties.setUrl("http://127.0.0.1:"+server.getAddress().getPort());
        properties.setBucket("private"); properties.setKey("server-test-jwt"); properties.setExpectedHost("127.0.0.1");
        storage=new SupabaseMaterialStorage(properties);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void missingObjectsAreIdempotentButBucketAndPermissionFailuresAreNot() {
        String ref=storage.reference(UUID.randomUUID(),"png");
        for(int status:List.of(400,404)) {
            deleteStatus=status; deleteBody="{\"code\":\"NoSuchKey\"}";
            assertThatCode(() -> storage.delete(ref)).doesNotThrowAnyException();
        }
        for(String code:List.of("NoSuchBucket","AccessDenied","UnknownError")) {
            deleteStatus=404; deleteBody="{\"code\":\""+code+"\",\"message\":\"SECRET\"}";
            assertThatThrownBy(() -> storage.delete(ref)).isInstanceOf(ApiException.class).hasMessageNotContaining("SECRET");
        }
        for(int status:List.of(401,403,500)) {
            deleteStatus=status; deleteBody="{\"code\":\"NoSuchKey\"}";
            assertThatThrownBy(() -> storage.delete(ref)).isInstanceOf(ApiException.class);
        }
        deleteStatus=404; deleteBody="not json";
        assertThatThrownBy(() -> storage.delete(ref)).isInstanceOf(ApiException.class);
    }

    @Test
    void privateUploadSignAndDeleteUseServerCredentialsAndOpaqueReference() {
        UUID user=UUID.randomUUID(); String reference=storage.reference(user,"pdf");
        assertThat(reference).startsWith("private/"+user+"/").doesNotContain("http");
        assertThat(storage.reference(user,"pdf")).isNotEqualTo(reference);
        var file=new MaterialUploadValidator().validate(List.of(new MockMultipartFile("file","name.pdf","application/pdf",new byte[]{1,2,3})),"LECTURE");
        storage.upload(reference,file);
        assertThat(uploaded).containsExactly(1,2,3);
        assertThat(authorization).isEqualTo("Bearer server-test-jwt"); assertThat(apiKey).isEqualTo("server-test-jwt");
        assertThat(storage.signedUrl(reference,600)).isEqualTo(properties.getUrl()+"/storage/v1/object/sign/"+reference+"?token=SECRET");
        assertThat(signBody).contains("600");
        storage.delete(reference);
        assertThat(calls).contains("DELETE /storage/v1/object/"+reference);
    }

    @Test
    void publicBucketsAndRedirectsAreRejected() {
        String ref=storage.reference(UUID.randomUUID(),"pdf");
        var file=new MaterialUploadValidator().validate(List.of(new MockMultipartFile("file","a.pdf","application/pdf",new byte[]{1})),"LECTURE");
        publicBucket=true;
        assertThatThrownBy(() -> storage.upload(ref,file)).isInstanceOf(ApiException.class);
        assertThat(calls).noneMatch(call -> call.startsWith("POST"));
        publicBucket=false; redirect=true;
        assertThatThrownBy(() -> storage.upload(ref,file)).isInstanceOf(ApiException.class);
        assertThat(calls).noneMatch(call -> call.contains("redirect-target"));
    }

    @Test
    void hostileSignedHostsAndPublishableKeyAreRejectedWithoutLeakingUrl() {
        String ref=storage.reference(UUID.randomUUID(),"pdf");
        signedOverride="https://evil.example/secret?token=SECRET";
        assertThatThrownBy(() -> storage.signedUrl(ref,600)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("evil");
        signedOverride=null; properties.setExpectedHost("wrong.example");
        assertThatThrownBy(() -> storage.signedUrl(ref,600)).isInstanceOf(ApiException.class);
        properties.setKey("sb_publishable_bad");
        assertThatThrownBy(() -> storage.reference(UUID.randomUUID(),"pdf")).isInstanceOf(ApiException.class);
    }

    @Test
    void pngReferencesSupportUploadSignAndDelete() {
        String reference=storage.reference(UUID.randomUUID(),"png");
        var file=new MaterialUploadValidator().validate(List.of(new MockMultipartFile("file","p.png","image/png",new byte[]{1})),"LECTURE");
        storage.upload(reference,file);
        assertThat(storage.signedUrl(reference,600)).contains(reference);
        storage.delete(reference);
        assertThat(calls).contains("DELETE /storage/v1/object/"+reference);
    }

    @Test
    void jpgReferencesSupportUploadSignAndDelete() {
        String reference=storage.reference(UUID.randomUUID(),"jpg");
        var file=new MaterialUploadValidator().validate(List.of(new MockMultipartFile("file","p.jpeg","image/jpeg",new byte[]{1})),"LECTURE");
        storage.upload(reference,file);
        assertThat(storage.signedUrl(reference,600)).contains(reference);
        storage.delete(reference);
        assertThat(calls).contains("DELETE /storage/v1/object/"+reference);
        assertThatThrownBy(() -> storage.delete(reference.replace(".jpg",".jpeg"))).isInstanceOf(ApiException.class);
    }
}
