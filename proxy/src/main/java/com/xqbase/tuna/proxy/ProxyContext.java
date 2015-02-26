package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;

public class ProxyContext implements Connector, Executor,
		UnaryOperator<String>, BiPredicate<String, String> {
	private Connector connector;
	private Executor executor;
	private SSLContext sslc;
	private UnaryOperator<String> lookup;
	private BiPredicate<String, String> auth;
	private String realm;
	private int logLevel;

	public ProxyContext(Connector connector, Executor executor, SSLContext sslc,
			UnaryOperator<String> lookup, BiPredicate<String, String> auth,
			String realm, int logLevel) {
		this.connector = connector;
		this.executor = executor;
		this.sslc = sslc;
		this.lookup = lookup;
		this.auth = auth;
		this.realm = realm;
		this.logLevel = logLevel;
	}

	@Override
	public Closeable add(ServerConnection serverConnection,
			String host, int port) throws IOException {
		return connector.add(serverConnection, host, port);
	}

	@Override
	public void connect(Connection connection, String host, int port)
			throws IOException {
		connector.connect(connection, host, port);
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

	public int getLogLevel() {
		return logLevel;
	}
}