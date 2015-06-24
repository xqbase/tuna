package com.xqbase.tuna.util;

import java.util.function.Consumer;

public class TimeoutQueue<T extends Expirable>
		extends LinkedEntry<T> implements Runnable {
	private Consumer<T> action;
	private int timeout;

	public TimeoutQueue(Consumer<T> action, int timeout) {
		super(null);
		this.action = action;
		this.timeout = timeout;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	@Override
	public void run() {
		long now = System.currentTimeMillis();
		iteratePrev(expirable -> expirable.getExpire() < now, action::accept);
	}
}