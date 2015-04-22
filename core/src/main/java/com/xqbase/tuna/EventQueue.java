package com.xqbase.tuna;

import java.util.concurrent.CountDownLatch;

@FunctionalInterface
public interface EventQueue {
	/**
	 * Executes a command in main thread.<p>
	 * <b>Can be called outside main thread.</b>
	 */
	public void invokeLater(Runnable runnable);

	/**
	 * Executes and waits for a command in main thread.<p>
	 * <b>MUST be called outside main thread.</b>
	 */
	public default void invokeAndWait(Runnable runnable) {
		CountDownLatch latch = new CountDownLatch(1);
		invokeLater(() -> {
			runnable.run();
			latch.countDown();
		});
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}