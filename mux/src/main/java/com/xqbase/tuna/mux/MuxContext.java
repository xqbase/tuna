package com.xqbase.tuna.mux;

import java.util.function.Predicate;

import com.xqbase.tuna.TimerHandler;

public class MuxContext implements TimerHandler, Predicate<byte[]> {
	private TimerHandler timerHandler;
	private Predicate<byte[]> auth;
	private int queueLimit;

	public MuxContext(TimerHandler timerHandler,
			Predicate<byte[]> auth, int queueLimit) {
		this.timerHandler = timerHandler;
		this.auth = auth;
		this.queueLimit = queueLimit;
	}

	@Override
	public Closeable postAtTime(Runnable runnable, long uptime) {
		return timerHandler.postAtTime(runnable, uptime);
	}

	@Override
	public boolean test(byte[] t) {
		return auth.test(t);
	}

	public int getQueueLimit() {
		return queueLimit;
	}
}