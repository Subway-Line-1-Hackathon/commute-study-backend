package com.likelion.commute_study_api.ai.judge;

import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.support.AiHttpSupport;

@Component
public class JudgeAiClientImpl implements JudgeAiClient {

	private final AiHttpSupport aiHttpSupport;

	public JudgeAiClientImpl(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@Override
	public JudgeResponse judge(JudgeRequest request) {
		return aiHttpSupport.post(
				"AI-03",
				"/v1/answers/judge-short",
				request,
				JudgeResponse.class,
				aiHttpSupport.timeoutSeconds("ai03")
		);
	}
}
