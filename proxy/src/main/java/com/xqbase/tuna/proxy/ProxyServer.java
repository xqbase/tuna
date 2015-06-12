package com.xqbase.tuna.proxy;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connector;
import com.xqbase.tuna.EventQueue;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.util.Log;
import com.xqbase.util.function.BiConsumerEx;

public class ProxyServer implements ServerConnection {
	private static final ClientConnection[] EMPTY_CLIENTS = {/**/};
	private static final ProxyConnection[] EMPTY_PROXIES = {/**/};

	private static SSLContext defaultSSLContext;

	static {
		try {
			defaultSSLContext = SSLContext.getInstance("TLS");
			defaultSSLContext.init(SSLManagers.DEFAULT_KEY_MANAGERS,
					SSLManagers.DEFAULT_TRUST_MANAGERS, null);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	Connector connector;
	EventQueue eventQueue;
	Executor executor;
	HashMap<String, ClientConnection> clientMap = new HashMap<>();
	HashMap<String, ClientConnection> secureClientMap = new HashMap<>();

	private HashSet<ProxyConnection> connections = new HashSet<>();
	private SSLContext sslc = defaultSSLContext;
	private BiPredicate<String, String> auth = (t, u) -> true;
	private UnaryOperator<String> lookup = t -> t;
	private BiConsumerEx<ProxyConnection, HttpPacket, RequestException>
			onRequest = (t, u) -> {/**/};
	private BiConsumer<ProxyConnection, HttpPacket> onResponse = (t, u) -> {/**/};
	private Consumer<ProxyConnection> onComplete = t -> {/**/};
	private IntFunction<byte[]> errorPages = ProxyConnection::getDefaultErrorPage;
	private String realm = null;
	private boolean enableReverse = false;
	private int forwardedType = ProxyConnection.FORWARDED_TRANSPARENT,
			logLevel = ProxyConnection.LOG_NONE;

	public ProxyServer(Connector connector, EventQueue eventQueue, Executor executor) {
		this.connector = connector;
		this.eventQueue = eventQueue;
		this.executor = executor;
	}

	@Override
	public ProxyConnection get() {
		return new ProxyConnection(this);
	}

	public HashSet<ProxyConnection> getConnections() {
		return connections;
	}

	public void disconnectAll() {
		for (ClientConnection client : clientMap.
				values().toArray(EMPTY_CLIENTS)) {
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				Log.v("Client Closed, " + client.toString(false));
			}
			client.getHandler().disconnect();
		}
		for (ClientConnection client : secureClientMap.
				values().toArray(EMPTY_CLIENTS)) {
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				Log.v("Client Closed, " + client.toString(false));
			}
			client.getHandler().disconnect();
		}
		for (ProxyConnection proxy : connections.toArray(EMPTY_PROXIES)) {
			proxy.getHandler().disconnect();
		}
		connections.clear();
	}

	public void setSSLContext(SSLContext sslc) {
		this.sslc = sslc;
	}

	public void setAuth(BiPredicate<String, String> auth) {
		this.auth = auth;
	}

	public void setLookup(UnaryOperator<String> lookup) {
		this.lookup = lookup;
	}

	public void setOnRequest(BiConsumerEx<ProxyConnection, HttpPacket, RequestException> onRequest) {
		this.onRequest = onRequest;
	}

	public void setOnResponse(BiConsumer<ProxyConnection, HttpPacket> onResponse) {
		this.onResponse = onResponse;
	}

	public void setOnComplete(Consumer<ProxyConnection> onComplete) {
		this.onComplete = onComplete;
	}

	public void setErrorPages(IntFunction<byte[]> errorPages) {
		this.errorPages = errorPages;
	}

	public void setRealm(String realm) {
		this.realm = realm;
	}

	public void setEnableReverse(boolean enableReverse) {
		this.enableReverse = enableReverse;
	}

	public void setForwardedType(int forwardedType) {
		this.forwardedType = forwardedType;
	}

	public void setLogLevel(int logLevel) {
		this.logLevel = logLevel;
	}

	public SSLContext getSSLContext() {
		return sslc;
	}

	public boolean auth(String username, String password) {
		return auth.test(username, password);
	}

	public String lookup(String host) {
		return lookup.apply(host);
	}

	public void onRequest(ProxyConnection bindings, HttpPacket request) throws RequestException {
		onRequest.accept(bindings, request);
	}

	public void onResponse(ProxyConnection bindings, HttpPacket response) {
		onResponse.accept(bindings, response);
	}

	public void onComplete(ProxyConnection bindings) {
		onComplete.accept(bindings);
	}

	public byte[] getErrorPage(int status) {
		return errorPages.apply(status);
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