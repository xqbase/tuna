package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.EventQueue;
import com.xqbase.tuna.ServerConnection;

public class ProxyContext implements Connector, EventQueue, Executor,
		UnaryOperator<String>, BiPredicate<String, String> {
	public static final int LOG_NONE = 0;
	public static final int LOG_DEBUG = 1;
	public static final int LOG_VERBOSE = 2;

	public static final int FORWARDED_TRANSPARENT = 0;
	public static final int FORWARDED_DELETE = 1;
	public static final int FORWARDED_OFF = 2;
	public static final int FORWARDED_TRUNCATE = 3;
	public static final int FORWARDED_ON = 4;

	private Connector connector;
	private EventQueue eventQueue;
	private Executor executor;
	private SSLContext sslc;
	private UnaryOperator<String> lookup;
	private BiPredicate<String, String> auth;
	private String realm;
	private boolean enableReverse;
	private int forwardedType, logLevel;

	public ProxyContext(Connector connector, EventQueue eventQueue,
			Executor executor, SSLContext sslc, UnaryOperator<String> lookup,
			BiPredicate<String, String> auth, String realm,
			boolean enableReverse, int forwardedType, int logLevel) {
		this.connector = connector;
		this.eventQueue = eventQueue;
		this.executor = executor;
		this.sslc = sslc;
		this.lookup = lookup;
		this.auth = auth;
		this.realm = realm;
		this.enableReverse = enableReverse;
		this.forwardedType = forwardedType;
		this.logLevel = logLevel;
	}

	@Override
	public Closeable add(ServerConnection serverConnection,
			InetSocketAddress socketAddress) throws IOException {
		return connector.add(serverConnection, socketAddress);
	}

	@Override
	public void connect(Connection connection,
			InetSocketAddress socketAddress) throws IOException {
		connector.connect(connection, socketAddress);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		eventQueue.invokeLater(runnable);
	}

	@Override
	public void execute(Runnable command) {
		executor.execute(command);
	}

	@Override
	public String apply(String host) {
		return lookup.apply(host);
	}

	@Override
	public boolean test(String username, String password) {
		return auth.test(username, password);
	}

	public SSLContext getSSLContext() {
		return sslc;
	}

	public String getRealm() {
		return realm;
	}

	public boolean isEnableReverse() {
		return enableReverse;
	}

	public int getForwardedType() {
		return forwardedType;
	}

	public int getLogLevel() {
		return logLevel;
	}
}