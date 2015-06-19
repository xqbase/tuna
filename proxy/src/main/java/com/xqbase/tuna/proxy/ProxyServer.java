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
import com.xqbase.tuna.proxy.util.LinkedEntry;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.util.Log;
import com.xqbase.util.Time;
import com.xqbase.util.function.BiConsumerEx;

public class ProxyServer implements ServerConnection {
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
	int keepAlive = (int) Time.MINUTE,
			forwardedType = ProxyConnection.FORWARDED_TRANSPARENT,
			logLevel = ProxyConnection.LOG_NONE,
			totalPeers = 0, idlePeers = 0;

	private HashSet<ProxyConnection> connections = new HashSet<>();
	private HashMap<String, LinkedEntry<ClientConnection>>
			plainClientMap = new HashMap<>(),
			secureClientMap = new HashMap<>();
	private LinkedEntry<ClientConnection> timeoutQueue = new LinkedEntry<>(null);

	private void disconnect(ClientConnection client) {
		client.disconnect();
		removeClient(client);
		if (logLevel >= ProxyConnection.LOG_VERBOSE) {
			Log.v("Client Keep-Alive Expired, " + client.toString(false));
		}
	}

	void incPeers() {
		totalPeers ++;
	}

	void decPeers() {
		totalPeers --;
	}

	ClientConnection borrowClient(String host, boolean secure) {
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(host);
		if (queue == null) {
			return null;
		}
		LinkedEntry<ClientConnection> entry = queue.getNext();
		removeClient(entry.getObject());
		return entry.getObject();
	}

	void returnClient(ClientConnection client) {
		client.clear();
		client.expire = System.currentTimeMillis() + keepAlive;
		client.linkedEntry = new LinkedEntry<>(client);
		client.timeoutEntry = new LinkedEntry<>(client);
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				client.secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(client.host);
		if (queue == null) {
			queue = new LinkedEntry<>(null);
			clientMap.put(client.host, queue);
		}
		queue.addNext(client.linkedEntry);
		timeoutQueue.addNext(client.timeoutEntry);
		idlePeers ++;
	}

	void removeClient(ClientConnection client) {
		client.linkedEntry.remove();
		client.timeoutEntry.remove();
		HashMap<String, LinkedEntry<ClientConnection>> clientMap =
				client.secure ? secureClientMap : plainClientMap;
		LinkedEntry<ClientConnection> queue = clientMap.get(client.host);
		if (queue != null && queue.getNext() == queue) {
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

	public HashSet<ProxyConnection> getConnections() {
		return connections;
	}

	public int getTotalPeers() {
		return totalPeers;
	}

	public int getIdlePeers() {
		return idlePeers;
	}

	public Runnable getSchedule() {
		return () -> {
			long now = System.currentTimeMillis();
			LinkedEntry<ClientConnection> timeoutEntry = timeoutQueue.getPrev();
			while (timeoutEntry != timeoutQueue && timeoutEntry.getObject().expire < now) {
				// disconnect breaks queue
				LinkedEntry<ClientConnection> entry = timeoutEntry.getPrev();
				disconnect(timeoutEntry.getObject());
				timeoutEntry = entry;
			}
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
				LinkedEntry<ClientConnection> entry = queue.getNext();
				while (entry != queue) {
					ClientConnection client = entry.getObject();
					sb.append(Integer.toHexString(client.hashCode())).append("/").
							append(client.toString(false)).append(", ");
					entry = entry.getNext();
				}
				out.println(sb.substring(0, sb.length() - 2) + "]");
			};
			out.println("Plain Client Map:");
			plainClientMap.forEach(action);
			out.println("Secure Client Map:");
			secureClientMap.forEach(action);
			out.println("Timeout Queue:");
			StringBuilder sb = new StringBuilder();
			LinkedEntry<ClientConnection> entry = timeoutQueue.getNext();
			while (entry != timeoutQueue) {
				ClientConnection client = entry.getObject();
				sb.append(Integer.toHexString(client.hashCode())).append("/").
						append(Time.toTimeString(client.expire, true)).append(", ");
				entry = entry.getNext();
			}
			out.print(sb.length() == 0 ? "<empty>" :
					"[" + sb.substring(0, sb.length() - 2) + "]");
			Log.v(sw.toString());
		};
	}

	public void disconnectAll() {
		LinkedEntry<ClientConnection> timeoutEntry = timeoutQueue.getPrev();
		while (timeoutEntry != timeoutQueue) {
			// disconnect breaks queue
			LinkedEntry<ClientConnection> entry = timeoutEntry.getPrev();
			disconnect(timeoutEntry.getObject());
			timeoutEntry = entry;
		}
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
		this.keepAlive = keepAlive;
	}

	public void setForwardedType(int forwardedType) {
		this.forwardedType = forwardedType;
	}

	public void setLogLevel(int logLevel) {
		this.logLevel = logLevel;
	}

/*
	public SSLContext getSSLContext() {
		return sslc;
	}

	public boolean auth(String username, String password) {
		return auth.test(username, password);
	}

	public String lookup(String host) {
		return lookup.apply(host);
	}

	public void onRequest(ProxyConnection proxy, HttpPacket request) throws RequestException {
		onRequest.accept(proxy, request);
	}

	public void onResponse(ProxyConnection proxy, HttpPacket response) {
		onResponse.accept(proxy, response);
	}

	public void onComplete(ProxyConnection proxy) {
		onComplete.accept(proxy);
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
*/
}