package com.likelion.commute_study_api.ai.material;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.support.AiAsyncConfig;
import com.likelion.commute_study_api.ai.support.AiHttpSupport;

@Component
public class MaterialAiClientImpl implements MaterialAiClient {

	private final AiHttpSupport aiHttpSupport;

	public MaterialAiClientImpl(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@Override
	@Async(AiAsyncConfig.AI_EXECUTOR)
	public CompletableFuture<MaterialProcessResponse> process(MaterialProcessRequest request) {
		MaterialProcessResponse response = aiHttpSupport.post(
				"AI-01",
				"/v1/materials/process",
				request,
				MaterialProcessResponse.class,
				aiHttpSupport.timeoutSeconds("ai01")
		);
		return CompletableFuture.completedFuture(response);
	}
}
