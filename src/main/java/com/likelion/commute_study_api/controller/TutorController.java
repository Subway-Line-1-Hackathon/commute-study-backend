package com.likelion.commute_study_api.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.*;
import com.likelion.commute_study_api.tutor.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestController
@SecurityRequirement(name="bearerAuth")
public class TutorController {
    private final CurrentUser currentUser;
    private final TutorQuota quota;
    private final TutorStreamService streams;
    private final AiProperties properties;
    private final ObjectMapper mapper=new ObjectMapper();
    public TutorController(CurrentUser currentUser,TutorQuota quota,TutorStreamService streams,AiProperties properties) {
        this.currentUser=currentUser; this.quota=quota; this.streams=streams; this.properties=properties;
    }
    @Operation(summary="AI 질문 남은 횟수",description="KST 날짜 기준. 성공한 done만 차감합니다.")
    @ApiResponse(responseCode="200",description="사용량",content=@Content(mediaType="application/json",
            examples=@ExampleObject(value="{\"used\":3,\"limit\":20,\"remaining\":17}")))
    @GetMapping("/api/users/me/ai-quota")
    public TutorQuota.Usage quota(@AuthenticationPrincipal Jwt jwt) { return quota.get(currentUser.authenticatedId(jwt)); }

    @Operation(summary="AI에게 실시간 질문",description="fetch 기반 POST SSE. 최근 history 6개만 전달. delta/done/error 이벤트. 시작 전 오류는 JSON이며 성공한 done만 차감합니다.")
    @ApiResponse(responseCode="200",description="SSE delta / done / error",content=@Content(mediaType="text/event-stream",
            examples=@ExampleObject(value="event: delta\ndata: {\"text\":\"설명\"}\n\n")))
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR")
    @ApiResponse(responseCode="404",description="NOT_FOUND: 본인 문제가 아니거나 없음")
    @ApiResponse(responseCode="409",description="STATE_CONFLICT: 질문 진행 중")
    @ApiResponse(responseCode="429",description="AI_QUESTION_LIMIT")
    @ApiResponse(responseCode="502",description="AI_UNAVAILABLE: 스트림 시작 전 실패")
    @ApiResponse(responseCode="504",description="AI_TIMEOUT: 스트림 시작 전 시간 초과")
    @PostMapping(value="/api/questions/{id}/ask",consumes=MediaType.APPLICATION_JSON_VALUE)
    public void ask(@PathVariable long id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody AskRequest input,
                    HttpServletRequest request,HttpServletResponse response) {
        var run=streams.prepare(id,currentUser.authenticatedId(jwt),input);
        try {
            var async=request.startAsync();
            async.setTimeout(properties.getTimeout().getOrDefault("ai05",15)*1000L);
            async.addListener(new AsyncListener() {
                public void onTimeout(AsyncEvent e) { run.timeout(); }
                public void onError(AsyncEvent e) { run.disconnect(); }
                public void onComplete(AsyncEvent e) { run.disconnect(); }
                public void onStartAsync(AsyncEvent e) {}
            });
            run.start(new TutorStreamService.Output() {
                public void event(String name,Object data) throws IOException {
                    response.setContentType("text/event-stream"); response.setCharacterEncoding("UTF-8");
                    response.setHeader("Cache-Control","no-cache"); response.setHeader("X-Accel-Buffering","no");
                    byte[] bytes=("event: "+name+"\ndata: "+mapper.writeValueAsString(data)+"\n\n").getBytes(StandardCharsets.UTF_8);
                    response.getOutputStream().write(bytes); response.flushBuffer();
                }
                public void startupError(ApiException error) throws IOException {
                    response.setStatus(error.getErrorCode().getStatus().value());
                    response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
                    response.getOutputStream().write(mapper.writeValueAsBytes(ErrorResponse.of(error))); response.flushBuffer();
                }
                public void complete() { try { async.complete(); } catch(IllegalStateException ignored) {} }
            });
        } catch(RuntimeException e) { run.disconnect(); throw e; }
    }

    // Explicit JSON content type also works when the client accepts only text/event-stream.
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> apiError(ApiException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus()).contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(e));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ErrorResponse.of(CommonErrorCode.VALIDATION_ERROR));
    }
}
