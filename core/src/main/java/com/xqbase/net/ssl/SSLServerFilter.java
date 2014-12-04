package com.xqbase.net.ssl;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

public class SSLServerFilter implements Supplier<SSLFilter> {
	private Executor executor;
	private SSLContext sslc;
	private int mode;

	public SSLServerFilter(Executor executor, SSLContext sslc, int mode) {
		this.executor = executor;
		this.sslc = sslc;
		this.mode = mode;
	}

	@Override
	public SSLFilter get() {
		return new SSLFilter(executor, sslc, mode);
	}
}