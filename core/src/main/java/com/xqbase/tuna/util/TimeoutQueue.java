package com.xqbase.tuna.util;

import java.util.function.Consumer;

public class TimeoutQueue<T extends Expirable<T>>
		extends LinkedEntry<T> implements Runnable {
	private Consumer<T> action;
	private int timeout;

	public TimeoutQueue(Consumer<T> action, int timeout) {
		super(null);
		this.action = action;
		this.timeout = timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void offer(T t) {
		t.setExpire(System.currentTimeMillis() + timeout);
		t.setTimeoutEntry(addNext(t));
	}

	@Override
	public void run() {
		long now = System.currentTimeMillis();
		iteratePrev(expirable -> expirable.getExpire() < now, action::accept);
	}
}