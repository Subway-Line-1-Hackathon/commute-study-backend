package com.likelion.commute_study_api.ai.feedback;

import org.springframework.stereotype.Component;

import com.likelion.commute_study_api.ai.support.AiHttpSupport;

@Component
public class FeedbackAiClientImpl implements FeedbackAiClient {

	private final AiHttpSupport aiHttpSupport;

	public FeedbackAiClientImpl(AiHttpSupport aiHttpSupport) {
		this.aiHttpSupport = aiHttpSupport;
	}

	@Override
	public FeedbackResponse feedback(FeedbackRequest request) {
		return aiHttpSupport.post(
				"AI-06",
				"/v1/sessions/feedback",
				request,
				FeedbackResponse.class,
				aiHttpSupport.timeoutSeconds("ai06")
		);
	}
}
