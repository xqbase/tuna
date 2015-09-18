package com.xqbase.tuna.proxy;

import java.io.PrintWriter;
import java.io.StringWriter;
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
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.tuna.util.LinkedEntry;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Log;
import com.xqbase.util.Time;
import com.xqbase.util.function.BiConsumerEx;

public class ProxyServer implements ServerConnection, Runnable {
	private static final int DEFAULT_TIMEOUT = (int) Time.MINUTE;
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
	TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(DEFAULT_TIMEOUT);
	SSLContext sslc = defaultSSLContext;
	BiPredicate<String, String> auth = (t, u) -> true;
	UnaryOperator<String> lookup = t -> t;
	BiConsumerEx<ProxyConnection, HttpPacket, RequestException>
			onRequest = (t, u) -> {/**/};
	BiConsumer<ProxyConnection, HttpPacket> onResponse = (t, u) -> {/**/};
	Consumer<ProxyConnection> onComplete = t -> {/**/};
	IntFunction<byte[]> errorPages = ProxyConnection::getDefaultErrorPage;
	String realm = null;
	boolean enableReverse = false;
	int forwardedType = ProxyConnection.FORWARDED_TRANSPARENT;
	int logLevel = ProxyConnection.LOG_NONE;
	int totalPeers = 0;
	int idlePeers = 0;
	TimeoutQueue<ProxyConnection> proxyTimeoutQueue = new TimeoutQueue<>(proxy -> {
		proxy.disconnect();
		if (logLevel >= ProxyConnection.LOG_VERBOSE) {
			Log.v("Proxy Connection Expired, " + proxy.remote);
		}
	}, DEFAULT_TIMEOUT);

	private HashSet<ProxyConnection> connections = new HashSet<>();
	private HashMap<String, LinkedEntry<ClientConnection>> plainClientMap = new HashMap<>();
	private HashMap<String, LinkedEntry<ClientConnection>> secureClientMap = new HashMap<>();
	private TimeoutQueue<ClientConnection> clientTimeoutQueue =
			new TimeoutQueue<>(client -> disconnect(client), DEFAULT_TIMEOUT);

	private void disconnect(ClientConnection client) {
		client.disconnect();
		removeClient(client);
		if (logLevel >= ProxyConnection.LOG_VERBOSE) {
			Log.v("Client Keep-Alive Expired, " + client.toString(false));
		}
	}

	ClientConnection borrowClient(String host, boolean secure) {
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(host);
		if (queue == null) {
			return null;
		}
		ClientConnection client = queue.getNext();
		removeClient(client);
		return client;
	}

	void returnClient(ClientConnection client) {
		client.clear();
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				client.secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(client.host);
		if (queue == null) {
			queue = new LinkedEntry<>(null);
			clientMap.put(client.host, queue);
		}
		client.linkedEntry = queue.addNext(client);
		clientTimeoutQueue.offer(client);
		idlePeers ++;
	}

	void removeClient(ClientConnection client) {
		client.linkedEntry.remove();
		client.timeoutEntry.remove();
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				client.secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(client.host);
		if (queue != null && queue.isEmpty()) {
			clientMap.remove(client.host);
		}
		idlePeers --;
	}

	public ProxyServer(Connector connector, EventQueue eventQueue, Executor executor) {
		this.connector = connector;
		this.eventQueue = eventQueue;
		this.executor = executor;
	}

	@Override
	public ProxyConnection get() {
		return new ProxyConnection(this);
	}

	@Override
	public void run() {
		ssltq.run();
		clientTimeoutQueue.run();
		proxyTimeoutQueue.run();
		if (logLevel < ProxyConnection.LOG_VERBOSE) {
			return;
		}

		// Dump Pool Info
		Log.v("Total Peers: " + totalPeers + ", Idle Peers: " + idlePeers);
		StringWriter sw = new StringWriter();
		PrintWriter out = new PrintWriter(sw);

		out.println("Dump Client Pool ...");
		BiConsumer<String, LinkedEntry<ClientConnection>> action = (host, queue) -> {
			StringBuilder sb = new StringBuilder();
			sb.append(host).append("=[");
			queue.iterateNext(client -> true, client ->
					sb.append(Integer.toHexString(client.hashCode())).append(": ").
					append(client.toString(false)).append(", "));
			out.println(sb.substring(0, sb.length() - 2) + "]");
		};
		out.println("Plain Client Map:");
		plainClientMap.forEach(action);
		out.println("Secure Client Map:");
		secureClientMap.forEach(action);

		out.println("Client Timeout Queue:");
		StringBuilder sbClients = new StringBuilder();
		clientTimeoutQueue.iterateNext(client -> true, client ->
				sbClients.append(Integer.toHexString(client.hashCode())).append('/').
				append(Time.toTimeString(client.expire, true)).append(", "));
		out.println(sbClients.length() == 0 ? "<empty>" :
				"[" + sbClients.substring(0, sbClients.length() - 2) + "]");

		out.println("Proxy Timeout Queue:");
		StringBuilder sbProxies = new StringBuilder();
		proxyTimeoutQueue.iterateNext(proxy -> true, proxy ->
				sbProxies.append(proxy.remote).append('/').
				append(Time.toTimeString(proxy.expire, true)).append(", "));
		out.print(sbProxies.length() == 0 ? "<empty>" :
			"[" + sbProxies.substring(0, sbProxies.length() - 2) + "]");

		Log.v(sw.toString());
	}

	public HashSet<ProxyConnection> getConnections() {
		return connections;
	}

	public int getTotalPeers() {
		return totalPeers;
	}

	public int getIdlePeers() {
		return idlePeers;
	}

	public void disconnectAll() {
		clientTimeoutQueue.iteratePrev(client -> true, client -> disconnect(client));
		for (ProxyConnection proxy : connections.toArray(EMPTY_PROXIES)) {
			proxy.disconnect();
		}
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

	public void setKeepAlive(int keepAlive) {
		ssltq.setTimeout(keepAlive);
		clientTimeoutQueue.setTimeout(keepAlive);
		proxyTimeoutQueue.setTimeout(keepAlive);
	}

	public void setForwardedType(int forwardedType) {
		this.forwardedType = forwardedType;
	}

	public void setLogLevel(int logLevel) {
		this.logLevel = logLevel;
	}
}