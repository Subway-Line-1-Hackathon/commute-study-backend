package com.likelion.commute_study_api.answer.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.likelion.commute_study_api.ai.judge.JudgeAiClient;
import com.likelion.commute_study_api.ai.judge.JudgeRequest;
import com.likelion.commute_study_api.ai.judge.JudgeResponse;
import com.likelion.commute_study_api.answer.AnswerNormalizer;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerRequest;
import com.likelion.commute_study_api.answer.dto.JudgeAnswerResponse;
import com.likelion.commute_study_api.answer.repository.AnswerJudgeRepository;
import com.likelion.commute_study_api.answer.repository.AnswerJudgeRepository.JudgeQuestion;
import com.likelion.commute_study_api.common.auth.CurrentUser;
import com.likelion.commute_study_api.common.exception.ApiException;
import com.likelion.commute_study_api.common.exception.CommonErrorCode;

/**
 * 단답형 판정(명세서 9.7, AI 서버 ↔ 백엔드 API 명세 7장).
 * <ol>
 * <li>답을 정규화해 정답 목록과 비교한다. 일치하면 AI 없이 정답</li>
 * <li>정규화 후 빈 답이면 AI 없이 오답(AI 서버와 같은 동작)</li>
 * <li>다르면 AI-03을 1회 호출한다(재시도 없음)</li>
 * <li>AI가 실패하거나 시간이 넘으면 {correct: null, timeout: true}로 자가 판정에 넘긴다(HTTP 200)</li>
 * </ol>
 * 판정만 하고 저장하지 않는다(저장은 POST /api/attempts). AI를 기다리는 동안 DB 연결을 잡지 않도록 트랜잭션을 쓰지 않는다.
 * 사용자 답안은 로그에 남기지 않는다(명세서 10장).
 */
@Service
public class AnswerJudgeService {

	private static final Logger log = LoggerFactory.getLogger(AnswerJudgeService.class);
	private static final String SHORT = "SHORT";

	private final CurrentUser currentUser;
	private final AnswerJudgeRepository repository;
	private final JudgeAiClient judgeAiClient;

	public AnswerJudgeService(CurrentUser currentUser, AnswerJudgeRepository repository, JudgeAiClient judgeAiClient) {
		this.currentUser = currentUser;
		this.repository = repository;
		this.judgeAiClient = judgeAiClient;
	}

	public JudgeAnswerResponse judge(Jwt jwt, JudgeAnswerRequest request) {
		UUID userId = currentUser.ensureUser(jwt);
		JudgeQuestion question = repository.findOwnedQuestion(userId, request.questionId())
				.orElseThrow(() -> new ApiException(CommonErrorCode.NOT_FOUND, "문제를 찾을 수 없습니다."));
		if (!SHORT.equals(question.type())) {
			throw new ApiException(CommonErrorCode.VALIDATION_ERROR, "단답 문제만 판정할 수 있습니다.",
					Map.of("questionId", "단답(SHORT) 문제가 아님: " + question.type()));
		}

		if (AnswerNormalizer.normalize(request.answer()).isEmpty()) {
			log.info("[단답] 판정 questionId={} result=BLANK", question.id());
			return JudgeAnswerResponse.blank();
		}
		List<String> acceptedAnswers = acceptedAnswers(question);
		if (AnswerNormalizer.matchesAny(request.answer(), acceptedAnswers)) {
			log.info("[단답] 판정 questionId={} result=MATCHED", question.id());
			return JudgeAnswerResponse.matched();
		}
		if (acceptedAnswers.isEmpty()) {
			// 정답 데이터가 없어 AI에도 보낼 수 없다(acceptedAnswers 1개 이상 필수). 사용자에게 맡긴다.
			log.warn("[단답] 정답 목록이 비어 자가 판정으로 넘김 questionId={}", question.id());
			return JudgeAnswerResponse.timedOut();
		}
		return judgeWithAi(question, acceptedAnswers, request.answer());
	}

	private JudgeAnswerResponse judgeWithAi(JudgeQuestion question, List<String> acceptedAnswers, String answer) {
		long start = System.currentTimeMillis();
		try {
			JudgeResponse result = judgeAiClient.judge(new JudgeRequest(question.stem(), acceptedAnswers,
					question.explanation() == null ? "" : question.explanation(), answer));
			log.info("[단답] 판정 questionId={} result=AI correct={} elapsedMs={}", question.id(), result.correct(),
					System.currentTimeMillis() - start);
			return JudgeAnswerResponse.judged(result.correct(), result.reason());
		} catch (ApiException e) {
			// AI_TIMEOUT·AI_UNAVAILABLE 등. AiHttpSupport가 원인을 이미 로그로 남겼다.
			log.warn("[단답] AI 판정 실패, 자가 판정으로 넘김 questionId={} code={} elapsedMs={}", question.id(),
					e.getErrorCode().getCode(), System.currentTimeMillis() - start);
			return JudgeAnswerResponse.timedOut();
		} catch (RuntimeException e) {
			// 응답 파싱 실패 등 예상 못 한 오류도 사용자를 막지 않고 자가 판정으로 넘긴다.
			log.error("[단답] AI 판정 중 예외, 자가 판정으로 넘김 questionId={}", question.id(), e);
			return JudgeAnswerResponse.timedOut();
		}
	}

	/** 인정 정답 목록. 비어 있으면 대표 정답 하나로 대신한다(빈 값은 뺀다). */
	private static List<String> acceptedAnswers(JudgeQuestion question) {
		List<String> accepted = question.acceptedAnswers().stream()
				.filter(value -> value != null && !value.isBlank())
				.toList();
		if (!accepted.isEmpty()) {
			return accepted;
		}
		return question.answer() == null || question.answer().isBlank() ? List.of() : List.of(question.answer());
	}
}
