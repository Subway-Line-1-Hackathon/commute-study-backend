package com.likelion.commute_study_api.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.likelion.commute_study_api.common.event.SessionFinishedEvent;
import com.likelion.commute_study_api.feedback.service.SessionFeedbackService;
import com.likelion.commute_study_api.feedback.service.SessionFeedbackService.Outcome;

class SessionFeedbackListenerTest {

	private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** generate 호출을 기록하는 가짜. release가 열릴 때까지 AI를 기다리는 것처럼 멈춘다. */
	static class RecordingService extends SessionFeedbackService {

		final List<Long> calls = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();
		final List<Preparation> preparations = new CopyOnWriteArrayList<>();
		final List<Long> dispatchFailures = new CopyOnWriteArrayList<>();
		final List<String> lifecycle = new CopyOnWriteArrayList<>();
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		volatile boolean block;

		RecordingService() {
			super(null, null);
		}

		@Override
		public void prepare(long sessionId, String reason) {
			preparations.add(new Preparation(sessionId, reason));
			lifecycle.add("prepare");
		}

		@Override
		public void failDispatch(long sessionId) {
			dispatchFailures.add(sessionId);
		}

		@Override
		public Outcome generate(long sessionId) {
			lifecycle.add("generate");
			calls.add(sessionId);
			threads.add(Thread.currentThread().getName());
			started.countDown();
			if (block) {
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return Outcome.SAVED;
		}
	}

	record Preparation(long sessionId, String reason) {
	}

	private static SessionFinishedEvent event(String status, String reason) {
		return new SessionFinishedEvent(1L, USER, status, reason);
	}

	// ---- 호출 조건(reason) ----

	@Test
	void 모두_풀어_COMPLETED면_부른다() {
		RecordingService service = new RecordingService();
		new SessionFeedbackListener(service, Runnable::run).onSessionFinished(event("COMPLETED", null));
		assertEquals(List.of(1L), service.calls);
	}

	@Test
	void 그만하기와_도착은_부른다() {
		RecordingService service = new RecordingService();
		SessionFeedbackListener listener = new SessionFeedbackListener(service, Runnable::run);

		listener.onSessionFinished(event("ENDED", "QUIT"));
		listener.onSessionFinished(event("ENDED", "ARRIVED"));

		assertEquals(2, service.calls.size());
	}

	@Test
	void 새로_시작과_다음_날_자동_종료는_부르지_않는다() {
		RecordingService service = new RecordingService();
		SessionFeedbackListener listener = new SessionFeedbackListener(service, Runnable::run);

		listener.onSessionFinished(event("ENDED", "NEW_START"));
		listener.onSessionFinished(event("ENDED", "NEXT_DAY"));

		assertTrue(service.calls.isEmpty());
	}

	@Test
	void 생략_사유도_커밋_전_준비에_전달한다() {
		RecordingService service = new RecordingService();
		SessionFeedbackListener listener = new SessionFeedbackListener(service, Runnable::run);

		listener.prepareFeedback(event("ENDED", "NEW_START"));
		listener.prepareFeedback(event("ENDED", "NEXT_DAY"));

		assertEquals(List.of(new Preparation(1L, "NEW_START"), new Preparation(1L, "NEXT_DAY")),
				service.preparations);
		assertTrue(service.calls.isEmpty());
	}

	@Test
	void 실행기가_거절하면_실패를_기록하고_종료_요청에는_던지지_않는다() {
		RecordingService service = new RecordingService();
		Executor rejecting = task -> { throw new RejectedExecutionException("queue full"); };
		SessionFeedbackListener listener = new SessionFeedbackListener(service, rejecting);

		assertDoesNotThrow(() -> listener.onSessionFinished(event("ENDED", "ARRIVED")));

		assertEquals(List.of(1L), service.dispatchFailures);
		assertTrue(service.calls.isEmpty());
	}

	// ---- 커밋 후 비동기 실행 (실제 Spring 프록시) ----

	/** DB 없이 트랜잭션 동기화(커밋 후 콜백)만 동작시키는 트랜잭션 매니저. */
	static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

		private final List<String> lifecycle;

		NoOpTransactionManager(List<String> lifecycle) {
			this.lifecycle = lifecycle;
		}

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
			lifecycle.add("commit");
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}

	@Configuration
	@EnableTransactionManagement // @TransactionalEventListener 처리기 등록
	@Import({ FeedbackAsyncConfig.class, SessionFeedbackListener.class })
	static class TestConfig {

		@Bean
		RecordingService recordingService() {
			return new RecordingService();
		}

		@Bean
		PlatformTransactionManager transactionManager(RecordingService service) {
			return new NoOpTransactionManager(service.lifecycle);
		}
	}

	@Nested
	class 비동기_실행 {

		private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
				TestConfig.class);
		private final RecordingService service = context.getBean(RecordingService.class);
		private final TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

		@AfterEach
		void close() {
			service.release.countDown();
			context.close();
		}

		@Test
		void 커밋_후_피드백_스레드에서_실행되고_발행한_쪽은_기다리지_않는다() throws InterruptedException {
			service.block = true; // AI가 오래 걸리는 상황

			long start = System.nanoTime();
			tx.executeWithoutResult(status -> context.publishEvent(event("ENDED", "ARRIVED")));
			long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			assertTrue(service.started.await(2, TimeUnit.SECONDS));
			assertTrue(elapsedMs < 1000, "종료 요청이 피드백을 기다렸다: " + elapsedMs + "ms");
			assertEquals(1, service.release.getCount()); // 피드백은 아직 진행 중
			assertTrue(service.threads.get(0).startsWith("feedback-"), service.threads.get(0));
			assertEquals(List.of(new Preparation(1L, "ARRIVED")), service.preparations);
			assertEquals(List.of("prepare", "commit", "generate"), service.lifecycle);
		}

		@Test
		void 준비는_이벤트_발행_즉시가_아니라_커밋_직전에_한다() {
			tx.executeWithoutResult(status -> {
				context.publishEvent(event("ENDED", "NEW_START"));
				assertTrue(service.preparations.isEmpty());
				assertTrue(service.calls.isEmpty());
			});

			assertEquals(List.of(new Preparation(1L, "NEW_START")), service.preparations);
			assertEquals(List.of("prepare", "commit"), service.lifecycle);
			assertTrue(service.calls.isEmpty());
		}

		@Test
		void 실제_피드백_대기열이_가득_차도_커밋은_성공하고_실패를_기록한다() {
			Executor executor = context.getBean(FeedbackAsyncConfig.FEEDBACK_EXECUTOR, Executor.class);
			CountDownLatch release = new CountDownLatch(1);
			Runnable blocking = () -> {
				try {
					release.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			};
			try {
				for (int i = 0; i < 52; i++) executor.execute(blocking);

				assertDoesNotThrow(() -> tx.executeWithoutResult(
						status -> context.publishEvent(event("ENDED", "ARRIVED"))));

				assertEquals(List.of(1L), service.dispatchFailures);
				assertEquals(List.of("prepare", "commit"), service.lifecycle);
				assertTrue(service.calls.isEmpty());
			} finally {
				release.countDown();
			}
		}

		@Test
		void 트랜잭션이_롤백되면_부르지_않는다() throws InterruptedException {
			tx.executeWithoutResult(status -> {
				context.publishEvent(event("ENDED", "ARRIVED"));
				status.setRollbackOnly();
			});

			assertFalse(service.started.await(500, TimeUnit.MILLISECONDS));
			assertTrue(service.preparations.isEmpty());
			assertTrue(service.dispatchFailures.isEmpty());
		}

		@Test
		void 트랜잭션_밖에서_발행되면_부르지_않는다() throws InterruptedException {
			// 모든 발행 지점이 @Transactional 안이다. 밖에서 발행되면 커밋 여부를 모르므로 무시한다.
			context.publishEvent(event("ENDED", "ARRIVED"));

			assertFalse(service.started.await(500, TimeUnit.MILLISECONDS));
			assertTrue(service.preparations.isEmpty());
			assertTrue(service.dispatchFailures.isEmpty());
		}
	}
}
