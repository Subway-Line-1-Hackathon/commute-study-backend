package com.likelion.commute_study_api.ai.material;

import java.util.concurrent.CompletableFuture;

/**
 * AI-01 단원화·요약. 최대 300초까지 걸릴 수 있어 비동기로 호출한다(AiAsyncConfig 전용 풀, 동시 3개).
 * 실패 시 UNSUPPORTED_FILE / PAGE_LIMIT_EXCEEDED / TEXT_EXTRACTION_FAILED / FILE_FETCH_FAILED는
 * AiCallException으로, 그 외는 ApiException(AiErrorCode)으로 던진다.
 */
public interface MaterialAiClient {

	CompletableFuture<MaterialProcessResponse> process(MaterialProcessRequest request);
}
