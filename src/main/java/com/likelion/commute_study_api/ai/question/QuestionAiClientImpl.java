package com.likelion.commute_study_api.ai.question;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.support.AiAsyncConfig;
import com.likelion.commute_study_api.ai.support.AiHttpSupport;

@Component
public class QuestionAiClientImpl implements QuestionAiClient {

	private final AiHttpSupport aiHttpSupport;

	public QuestionAiClientImpl(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@Override
	@Async(AiAsyncConfig.AI_EXECUTOR)
	public CompletableFuture<QuestionGenerateResponse> generate(QuestionGenerateRequest request) {
		QuestionGenerateResponse response = aiHttpSupport.post(
				"AI-02",
				"/v1/questions/generate",
				request,
				QuestionGenerateResponse.class,
				aiHttpSupport.timeoutSeconds("ai02")
		);
		return CompletableFuture.completedFuture(response);
	}
}
