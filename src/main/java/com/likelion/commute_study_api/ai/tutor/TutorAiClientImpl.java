package com.likelion.commute_study_api.ai.tutor;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.support.AiErrorBody;
import com.likelion.commute_study_api.ai.support.MockErrorContext;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TutorAiClientImpl implements TutorAiClient {
    private static final Logger log=LoggerFactory.getLogger(TutorAiClientImpl.class);
    private final ObjectMapper mapper=new ObjectMapper();
    private final AiProperties properties;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    public TutorAiClientImpl(AiProperties properties) { this.properties=properties; }

    @Override public void ask(TutorAskRequest request,TutorEventListener listener) {
        int seconds=properties.getTimeout().getOrDefault("ai05",15);
        var expired=new AtomicBoolean();
        var body=new AtomicReference<InputStream>();
        Thread caller=Thread.currentThread();
        // HttpRequest.timeout alone does not bound consumption of a streaming response body.
        Thread deadline=Thread.ofVirtual().name("tutor-http-deadline").start(()-> {
            try { Thread.sleep(Duration.ofSeconds(seconds)); expired.set(true); caller.interrupt(); close(body.get()); }
            catch(InterruptedException ignored) {}
        });
        try {
            var builder=HttpRequest.newBuilder(URI.create(properties.getServerUrl()+"/v1/tutor/ask"))
                    .timeout(Duration.ofSeconds(seconds)).header("X-Internal-Key",properties.getInternalKey())
                    .header("Content-Type","application/json").header("Accept","text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)));
            String mockError=mockErrorToForward();
            if(mockError!=null) {
                builder.header(MockErrorContext.HEADER,mockError);
                log.warn("[AI-05] 개발용 X-Mock-Error 전달 code={}",mockError);
            }
            var http=builder.build();
            var response=client.send(http,HttpResponse.BodyHandlers.ofInputStream());
            body.set(response.body());
            if(response.statusCode()!=200) throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                parse(reader,listener);
            }
        } catch(HttpTimeoutException e) { throw new ApiException(AiErrorCode.AI_TIMEOUT); }
        catch(InterruptedException e) {
            if(!expired.get()) Thread.currentThread().interrupt();
            throw new ApiException(expired.get()?AiErrorCode.AI_TIMEOUT:AiErrorCode.AI_UNAVAILABLE);
        } catch(IOException e) { throw new ApiException(expired.get()?AiErrorCode.AI_TIMEOUT:AiErrorCode.AI_UNAVAILABLE); }
        finally { deadline.interrupt(); close(body.get()); if(expired.get()) Thread.interrupted(); }
    }

    /** ai.mock-error-passthrough가 켜져 있을 때만, 질문 요청의 X-Mock-Error 코드(AI-05 지정 포함). */
    String mockErrorToForward() {
        return properties.isMockErrorPassthrough()?MockErrorContext.codeFor("AI-05"):null;
    }

    private void parse(BufferedReader reader,TutorEventListener listener) throws IOException {
        String type=""; var data=new StringBuilder(); String line;
        while((line=reader.readLine())!=null) {
            if(Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            if(line.isEmpty()) {
                if(dispatch(type,data.toString(),listener)) return;
                type=""; data.setLength(0);
            } else if(line.startsWith("event:")) type=line.substring(6).strip();
            else if(line.startsWith("data:")) {
                String value=line.substring(5); if(value.startsWith(" ")) value=value.substring(1);
                if(!data.isEmpty()) data.append('\n'); data.append(value);
            }
        }
        if(!data.isEmpty() && dispatch(type,data.toString(),listener)) return;
        throw new ApiException(AiErrorCode.AI_UNAVAILABLE);
    }
    private boolean dispatch(String type,String data,TutorEventListener listener) throws IOException {
        switch(type) {
            case "delta" -> {
                var event=mapper.readValue(data,TutorDeltaEvent.class);
                if(event.text()==null) throw new IOException("Invalid delta");
                listener.onDelta(event.text());
            }
            case "done" -> {
                var event=mapper.readValue(data,TutorDoneEvent.class);
                if(event.meta()==null) throw new IOException("Invalid done");
                listener.onDone(event.meta()); return true;
            }
            case "error" -> {
                var event=mapper.readValue(data,AiErrorBody.class);
                if(event.error()==null) throw new IOException("Invalid error");
                listener.onError(event.error().code(),event.error().message()); return true;
            }
            default -> { /* Comments and unknown events do not complete a stream. */ }
        }
        return false;
    }
    private static void close(InputStream stream) { if(stream!=null) try { stream.close(); } catch(IOException ignored) {} }
    @jakarta.annotation.PreDestroy public void close() { client.shutdownNow(); }
}
