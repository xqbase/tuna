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
	private int keepAlive = (int) Time.MINUTE,
			forwardedType = ProxyConnection.FORWARDED_TRANSPARENT,
			logLevel = ProxyConnection.LOG_NONE;
	private HashMap<String, ClientConnection> plainClientMap = new HashMap<>(),
			secureClientMap = new HashMap<>();
	private TimeoutEntry timeoutQueue = new TimeoutEntry(null, 0);

	private void disconnect(ClientConnection client) {
		client.getHandler().disconnect();
		removeClient(client);
		if (logLevel >= ProxyConnection.LOG_VERBOSE) {
			Log.v("Client Keep-Alive Expired, " + client.toString(false));
		}
	}

	ClientConnection borrowClient(String host, boolean secure) {
		HashMap<String, ClientConnection> clientMap =
				secure ? secureClientMap : plainClientMap;
		ClientConnection queue = clientMap.get(host);
		if (queue == null) {
			return null;
		}
		ClientConnection client = queue.getNext();
		if (client != null) {
			removeClient(client);
		}
		return client;
	}

	void returnClient(ClientConnection client) {
		TimeoutEntry timeoutEntry = new TimeoutEntry(client,
				System.currentTimeMillis() + keepAlive);
		client.setTimeoutEntry(timeoutEntry);
		HashMap<String, ClientConnection> clientMap =
				client.isSecure() ? secureClientMap : plainClientMap;
		ClientConnection queue = clientMap.get(client.getHost());
		if (queue == null) {
			queue = new ClientConnection(null, null, null, false, false, null, 0);
			clientMap.put(client.getHost(), queue);
		}
		queue.addNext(client);
		timeoutQueue.addNext(timeoutEntry);
	}

	void removeClient(ClientConnection client) {
		client.remove();
		client.getTimeoutEntry().remove();
		String host = client.getHost();
		HashMap<String, ClientConnection> clientMap =
				client.isSecure() ? secureClientMap : plainClientMap;
		ClientConnection queue = clientMap.get(host);
		if (queue != null && queue.getNext() == queue) {
			clientMap.remove(host);
		}
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

	public Runnable getSchedule() {
		return () -> {
			long now = System.currentTimeMillis();
			TimeoutEntry timeout = timeoutQueue.getPrev();
			while (timeout != timeoutQueue && timeout.expire < now) {
				// disconnect breaks queue
				TimeoutEntry timeout_ = timeout.getPrev();
				disconnect(timeout.client);
				timeout = timeout_;
			}
			if (logLevel < ProxyConnection.LOG_VERBOSE) {
				return;
			}
			// Dump Pool Info
			StringWriter sw = new StringWriter();
			PrintWriter out = new PrintWriter(sw);
			out.println("Dump Client Pool ...");
			BiConsumer<String, ClientConnection> action = (host, queue) -> {
				StringBuilder sb = new StringBuilder();
				sb.append(host).append("=[");
				ClientConnection client = queue.getNext();
				while (client != queue) {
					sb.append(Integer.toHexString(client.hashCode())).append("/").
							append(client.toString(false)).append(", ");
					client = client.getNext();
				}
				out.println(sb.substring(0, sb.length() - 2) + "]");
			};
			out.println("Plain Client Map:");
			plainClientMap.forEach(action);
			out.println("Secure Client Map:");
			secureClientMap.forEach(action);
			out.println("Timeout Queue:");
			StringBuilder sb = new StringBuilder();
			timeout = timeoutQueue.getNext();
			while (timeout != timeoutQueue) {
				sb.append(Integer.toHexString(timeout.client.hashCode())).append("/").
						append(Time.toTimeString(timeout.expire, true)).append(", ");
				timeout = timeout.getNext();
			}
			out.print(sb.length() == 0 ? "<empty>" :
					"[" + sb.substring(0, sb.length() - 2) + "]");
			Log.v(sw.toString());
		};
	}

	public void disconnectAll() {
		TimeoutEntry timeout = timeoutQueue.getPrev();
		while (timeout != timeoutQueue) {
			// disconnect breaks queue
			TimeoutEntry timeout_ = timeout.getPrev();
			disconnect(timeout.client);
			timeout = timeout_;
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
}