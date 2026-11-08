package com.likelion.commute_study_api.ai.tutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import com.likelion.commute_study_api.ai.AiErrorCode;
import com.likelion.commute_study_api.ai.support.AiMeta;
import com.likelion.commute_study_api.common.config.AiProperties;
import com.likelion.commute_study_api.common.exception.ApiException;

class TutorAiClientImplTest {

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private TutorAiClientImpl client(int port) {
		AiProperties props = new AiProperties();
		props.setServerUrl("http://localhost:" + port);
		props.setInternalKey("test-key");
		return new TutorAiClientImpl(props);
	}

	private HttpServer startSseServer(int status, String sseBody) throws IOException {
		HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		s.createContext("/", exchange -> {
			byte[] bytes = sseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(status, 0); // 0 = 청크 전송(스트리밍)
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
				os.flush();
			}
		});
		s.start();
		return s;
	}

	private record RecordingListener(List<String> deltas, List<AiMeta> dones, List<String[]> errors)
			implements TutorEventListener {

		RecordingListener() {
			this(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
		}

		@Override
		public void onDelta(String text) {
			deltas.add(text);
		}

		@Override
		public void onDone(AiMeta meta) {
			dones.add(meta);
		}

		@Override
		public void onError(String code, String message) {
			errors.add(new String[] { code, message });
		}
	}

	@Test
	void 델타가_순서대로_오고_done으로_끝난다() throws IOException {
		String sse = "event: delta\ndata: {\"text\":\"큐는 \"}\n\n"
				+ "event: delta\ndata: {\"text\":\"대기열에 써요.\"}\n\n"
				+ "event: done\ndata: {\"meta\":{\"model\":\"mock\",\"inputTokens\":1,\"outputTokens\":2,\"elapsedMs\":10,\"attempts\":1}}\n\n";
		server = startSseServer(200, sse);
		TutorAiClientImpl client = client(server.getAddress().getPort());
		RecordingListener listener = new RecordingListener();

		client.ask(sampleRequest(), listener);

		assertEquals(List.of("큐는 ", "대기열에 써요."), listener.deltas());
		assertEquals(1, listener.dones().size());
		assertEquals("mock", listener.dones().get(0).model());
		assertTrue(listener.errors().isEmpty());
	}

	@Test
	void 스트림_중간_에러는_onError로_전달되고_먼저_온_delta는_살아있다() throws IOException {
		String sse = "event: delta\ndata: {\"text\":\"일부 답변\"}\n\n"
				+ "event: error\ndata: {\"error\":{\"code\":\"LLM_FAILED\",\"message\":\"모델 오류\"}}\n\n";
		server = startSseServer(200, sse);
		TutorAiClientImpl client = client(server.getAddress().getPort());
		RecordingListener listener = new RecordingListener();

		client.ask(sampleRequest(), listener);

		assertEquals(List.of("일부 답변"), listener.deltas());
		assertEquals(1, listener.errors().size());
		assertEquals("LLM_FAILED", listener.errors().get(0)[0]);
		assertTrue(listener.dones().isEmpty());
	}

	@Test
	void 시작_전_오류_401은_스트림_없이_ApiException으로_던져진다() throws IOException {
		server = startSseServer(401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"키가 틀렸습니다\"}}");
		TutorAiClientImpl client = client(server.getAddress().getPort());
		RecordingListener listener = new RecordingListener();

		ApiException e = assertThrows(ApiException.class, () -> client.ask(sampleRequest(), listener));

		assertEquals(AiErrorCode.AI_UNAVAILABLE, e.getErrorCode());
		assertTrue(listener.deltas().isEmpty());
		assertTrue(listener.errors().isEmpty()); // 시작 전 오류는 listener가 아니라 예외로만 전달됨
	}

	private TutorAskRequest sampleRequest() {
		return new TutorAskRequest(
				new TutorAskRequest.Category(1L, "스택과 큐", List.of()),
				new TutorAskRequest.Question("다음 중 FIFO 구조는?", List.of(), "2", "...", "1"),
				new ArrayList<>(),
				"그럼 큐는 어디에 써요?"
		);
	}

    @Test void malformedEventAndEofWithoutDoneAreFailures() throws IOException {
        server=startSseServer(200,"event: delta\ndata: broken\n\n");
        assertThrows(ApiException.class,()->client(server.getAddress().getPort()).ask(sampleRequest(),new RecordingListener()));
        server.stop(0);
        server=startSseServer(200,"event: delta\ndata: {\"text\":\"partial\"}\n\n");
        var listener=new RecordingListener();
        assertThrows(ApiException.class,()->client(server.getAddress().getPort()).ask(sampleRequest(),listener));
        assertEquals(List.of("partial"),listener.deltas()); assertTrue(listener.dones().isEmpty());
    }

    @Test void listenerFailureIsNotSwallowed() throws IOException {
        server=startSseServer(200,"event: delta\ndata: {\"text\":\"partial\"}\n\n");
        var listener=new TutorEventListener() {
            public void onDelta(String text) { throw new IllegalStateException("downstream"); }
            public void onDone(AiMeta meta) {}
            public void onError(String code,String message) {}
        };
        assertThrows(IllegalStateException.class,()->client(server.getAddress().getPort()).ask(sampleRequest(),listener));
    }

    @Test void multiLineDataAndTerminalWithoutFinalBlankLineAreParsedOnce() throws IOException {
        server=startSseServer(200,": heartbeat\n\nevent: done\ndata: {\"meta\":\ndata: {\"model\":\"mock\",\"inputTokens\":0,\"outputTokens\":0,\"elapsedMs\":0,\"attempts\":1}}");
        var listener=new RecordingListener(); client(server.getAddress().getPort()).ask(sampleRequest(),listener);
        assertEquals(1,listener.dones().size());
    }

    @Test void stalledBodyIsBoundedByTotalDeadline() throws IOException {
        server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        var release=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/",exchange->{
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                out.write("event: delta\ndata: {\"text\":\"partial\"}\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }); server.start();
        var props=new AiProperties(); props.setServerUrl("http://localhost:"+server.getAddress().getPort()); props.setInternalKey("test"); props.getTimeout().put("ai05",1);
        var listener=new RecordingListener(); var client=new TutorAiClientImpl(props);
        try {
            long start=System.nanoTime();
            var error=assertThrows(ApiException.class,()->client.ask(sampleRequest(),listener));
            assertEquals(AiErrorCode.AI_TIMEOUT,error.getErrorCode());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()<4);
            assertEquals(List.of("partial"),listener.deltas());
        } finally { release.countDown(); client.close(); }
    }
}
