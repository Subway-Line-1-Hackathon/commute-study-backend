package com.likelion.commute_study_api.tutor;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.support.*;
import com.likelion.commute_study_api.ai.tutor.*;
import com.likelion.commute_study_api.common.exception.*;

@Service
public class TutorStreamService {
    private static final Logger log=LoggerFactory.getLogger(TutorStreamService.class);
    private final TutorContextRepository context;
    private final TutorQuota quota;
    private final TutorAiClient ai;
    private final ExecutorService executor;
    private final Set<UUID> active=ConcurrentHashMap.newKeySet();
    public TutorStreamService(TutorContextRepository context,TutorQuota quota,TutorAiClient ai,
                              @Qualifier("tutorExecutor") ExecutorService executor) {
        this.context=context; this.quota=quota; this.ai=ai; this.executor=executor;
    }
    public Run prepare(long id,UUID user,AskRequest input) {
        var request=context.request(id,user,input);
        if(!active.add(user)) throw new ApiException(CommonErrorCode.STATE_CONFLICT);
        try { quota.requireAvailable(user); }
        catch(RuntimeException e) { active.remove(user); throw e; }
        return new Run(user,request);
    }
    public interface Output {
        void event(String name,Object data) throws IOException;
        void startupError(ApiException error) throws IOException;
        void complete();
    }
    public final class Run implements TutorEventListener {
        private final UUID user;
        private final TutorAskRequest request;
        private Output output;
        private boolean started,ended;
        private FutureTask<Void> task;
        private Run(UUID user,TutorAskRequest request) { this.user=user; this.request=request; }
        public synchronized void start(Output output) {
            this.output=output;
            if(ended) { output.complete(); return; }
            task=new FutureTask<>(()-> {
                try { ai.ask(request,this); synchronized(this) { if(!ended) fail(new ApiException(AiErrorCode.AI_UNAVAILABLE)); } }
                catch(Exception e) { fail(e instanceof ApiException a?a:new ApiException(AiErrorCode.AI_UNAVAILABLE)); }
                return null;
            });
            // 개발용 X-Mock-Error를 질문 스레드로 넘긴다(task.cancel은 FutureTask를 직접 취소하므로 그대로 동작)
            try { executor.execute(MockErrorContext.wrap(task)); }
            catch(RuntimeException e) { fail(new ApiException(AiErrorCode.AI_UNAVAILABLE)); }
        }
        public synchronized void onDelta(String text) {
            if(ended) return;
            try { output.event("delta",new TutorDeltaEvent(text)); started=true; }
            catch(IOException e) { disconnect(); }
        }
        public synchronized void onDone(AiMeta meta) {
            if(ended) return;
            try {
                var chargedDay=quota.charge(user); // Transaction commits before a successful done is sent.
                try { output.event("done",new TutorDoneEvent(meta)); }
                catch(Exception e) {
                    try { quota.refund(user,chargedDay); }
                    catch(Exception refundFailure) { log.error("AI 질문 사용량 보상 실패 userId={} date={}",user,chargedDay); }
                    throw e;
                }
                finish();
            } catch(IOException e) { disconnect(); }
            catch(Exception e) { fail(e instanceof ApiException a?a:new ApiException(CommonErrorCode.INTERNAL_ERROR)); }
        }
        public synchronized void onError(String code,String message) {
            if(ended) return;
            try { output.event("error",new AiErrorBody(new AiErrorBody.AiError(
                    code==null?"AI_UNAVAILABLE":code,message==null?"AI 응답 처리에 실패했습니다.":message))); }
            catch(IOException ignored) { /* Disconnected clients cannot receive another event. */ }
            finally { finish(); }
        }
        private synchronized void fail(ApiException error) {
            if(ended) return;
            try {
                if(started) output.event("error",new AiErrorBody(new AiErrorBody.AiError(error.getErrorCode().getCode(),error.getMessage())));
                else output.startupError(error);
            } catch(IOException ignored) { /* No charge on failure. */ }
            finally { finish(); }
        }
        public synchronized void timeout() {
            if(ended) return;
            fail(new ApiException(AiErrorCode.AI_TIMEOUT));
            if(task!=null) task.cancel(true);
        }
        public synchronized void disconnect() {
            if(ended) return;
            finish();
            if(task!=null) task.cancel(true);
        }
        private void finish() {
            ended=true; active.remove(user);
            if(output!=null) output.complete();
        }
    }
}
