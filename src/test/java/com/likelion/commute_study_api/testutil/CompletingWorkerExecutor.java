package com.likelion.commute_study_api.testutil;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finishes each task on a real worker before execute returns, forcing a
 * supplyAsync future to finish before its continuation can be registered.
 */
public final class CompletingWorkerExecutor implements Executor, AutoCloseable {

	private final AtomicReference<Thread> workerThread = new AtomicReference<>();
	private final ExecutorService worker;

	public CompletingWorkerExecutor(String name) {
		worker = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, name);
			workerThread.set(thread);
			return thread;
		});
	}

	@Override
	public void execute(Runnable task) {
		if (Thread.currentThread() == workerThread.get()) {
			task.run();
			return;
		}
		try {
			worker.submit(task).get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while completing test worker task", e);
		} catch (Exception e) {
			throw new AssertionError("Test worker task did not finish", e);
		}
	}

	@Override
	public void close() throws InterruptedException {
		worker.shutdownNow();
		if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
			throw new AssertionError("Test worker did not stop");
		}
	}
}
