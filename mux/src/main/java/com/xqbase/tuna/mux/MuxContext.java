package com.xqbase.tuna.mux;

import java.util.function.Predicate;

import com.xqbase.tuna.TimerHandler;

public class MuxContext implements TimerHandler, Predicate<byte[]> {
	public static final int LOG_NONE = 0;
	public static final int LOG_DEBUG = 1;
	public static final int LOG_VERBOSE = 2;

	private TimerHandler timerHandler;
	private Predicate<byte[]> auth;
	private int queueLimit, logLevel;

	/**
	 * @param auth <i>auth-predicate</i> for Server or
	 *        <i>auth/listen-error-callback</i> for Client
	 */
	public MuxContext(TimerHandler timerHandler,
			Predicate<byte[]> auth, int queueLimit, int logLevel) {
		this.timerHandler = timerHandler;
		this.auth = auth;
		this.queueLimit = queueLimit;
		this.logLevel = logLevel;
	}

	@Override
	public Closeable postAtTime(Runnable runnable, long uptime) {
		return timerHandler.postAtTime(runnable, uptime);
	}

	@Override
	public boolean test(byte[] t) {
		return auth.test(t);
	}

	public boolean isQueueStatusChanged(int size, int[] lastSize) {
		if (queueLimit < 0) {
			return false;
		}
		if (size > queueLimit) {
			boolean changed = size > lastSize[0];
			lastSize[0] = size;
			return changed;
		}
		if (size > 0 || lastSize[0] == 0) {
			return false;
		}
		lastSize[0] = 0;
		return true;
	}

	public int getLogLevel() {
		return logLevel;
	}
}