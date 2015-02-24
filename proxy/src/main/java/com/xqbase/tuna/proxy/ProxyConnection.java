package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.net.URL;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.http.HttpPacketException;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.util.Log;

/** Connection for <b>CONNECT</b> */
class PeerConnection implements Connection {
	private static final int LOG_DEBUG = ProxyConnection.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyConnection.LOG_VERBOSE;

	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	private ProxyConnection peer;
	private ConnectionHandler peerHandler, handler;
	private boolean established = false;
	// For DEBUG only
	private int logLevel, port;
	private String host;

	PeerConnection(ProxyConnection peer, ConnectionHandler peerHandler,
			int logLevel, String host, int port) {
		this.peer = peer;
		this.peerHandler = peerHandler;
		this.logLevel = logLevel;
		this.host = host;
		this.port = port;
	}

	String toString(boolean recv) {
		return peer.getRemote() + (recv ? " <= " : " => ") + host + ":" + port;
	}

	ConnectionHandler getHandler() {
		return handler;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		peerHandler.send(b, off, len);
	}

	@Override
	public void onQueue(int delta, int total) {
		peerHandler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((total == 0 ? "Connection Unblocked, " :
					"Connection Blocked, ") + toString(false));
		}
	}

	@Override
	public void onConnect() {
		peerHandler.send(CONNECTION_ESTABLISHED);
		established = true;
		if (logLevel >= LOG_VERBOSE) {
			Log.v("Connection Established, " + toString(false));
		}
	}

	@Override
	public void onDisconnect() {
		if (!established) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Connection Failed, " + toString(false));
			}
			peerHandler.send(ProxyConnection.GATEWAY_TIMEOUT);
		} else if (logLevel >= LOG_VERBOSE) {
			Log.v("Connection Lost, " + toString(true));
		}
		peer.disconnect();
	}
}

/** Connection for request except <b>CONNECT</b> */
class ClientConnection implements Connection {
	private static final int LOG_DEBUG = ProxyConnection.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyConnection.LOG_VERBOSE;

	private ProxyConnection proxy;
	private ConnectionHandler proxyHandler, handler;
	private HttpPacket request, response = new HttpPacket();
	private boolean secure, established = false, begun = false, chunked = false,
			requestClose = false, responseClose = false;
	private int logLevel;
	private String host;

	ClientConnection(ProxyConnection proxy, ConnectionHandler proxyHandler,
			int logLevel, HttpPacket request, boolean secure, String host) {
		this.proxy = proxy;
		this.proxyHandler = proxyHandler;
		this.logLevel = logLevel;
		this.request = request;
		this.secure = secure;
		this.host = host;
	}

	String toString(boolean recv) {
		String path = request.getPath();
		return proxy.getRemote() + (recv ? " <= " : " => ") +
				(secure ? "https://" : "http://") + host + (path == null ? "" : path) +
				" (" + handler.getLocalAddr() + ":" + handler.getLocalPort() + ")";
	}

	boolean isBegun() {
		return begun;
	}

	void begin(boolean head, boolean connectionClose) {
		begun = chunked = false;
		response.setType(head ? HttpPacket.TYPE_RESPONSE_HEAD : request.isHttp10() ?
				HttpPacket.TYPE_RESPONSE_HTTP10 : HttpPacket.TYPE_RESPONSE);
		requestClose = connectionClose;
		sendRequest(true);
	}

	void remove() {
		proxy.getClientMap(secure).remove(host);
	}

	ConnectionHandler getHandler() {
		return handler;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (!proxy.isCurrentClient(this) || response.isComplete()) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unexpected Response: \"" + new String(b, off, len) +
						"\", " + toString(true));
			}
			handler.disconnect();
			remove();
			return;
		}
		ByteArrayQueue queue = new ByteArrayQueue(b, off, len);
		try {
			response.read(queue);
		} catch (HttpPacketException e) {
			if (logLevel >= LOG_DEBUG) {
				Log.d(e.getMessage() + ", " + toString(true));
			}
			// Disconnect for a Bad Response
			if (!response.isCompleteHeader()) {
				handler.send(ProxyConnection.BAD_GATEWAY);
			}
			proxy.disconnect();
			return;
		}
		if (begun) {
			responseClose = responseClose || queue.length() > 0;
			sendResponse(false);
			return;
		}
		if (!response.isCompleteHeader()) {
			return;
		}
		if (logLevel >= LOG_VERBOSE) {
			Log.v("Response Header Received, " + toString(true));
		}
		begun = true;
		responseClose = requestClose || response.isHttp10() ||
				response.testHeader("CONNECTION", "close", true, true) ||
				queue.length() > 0;
		// Write in Chunked mode when Request is HTTP/1.1 and
		// Response is HTTP/1.0 and has no Content-Length
		if (!request.isHttp10() && response.isHttp10() &&
				response.getHeader("CONTENT-LENGTH") == null) {
			chunked = true;
			response.setHeader("Transfer-Encoding", "chunked");
		}
		response.setHttp10(request.isHttp10());
		response.setHeader("Connection", requestClose ? "close" : "keep-alive");
		sendResponse(true);
	}

	@Override
	public void onQueue(int delta, int total) {
		if (!proxy.isCurrentClient(this)) {
			return;
		}
		proxyHandler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((total == 0 ? "Request Unblocked, " :
					"Request Blocked, ") + toString(false));
		}
	}

	@Override
	public void onConnect() {
		established = true;
	}

	@Override
	public void onDisconnect() {
		remove();
		if (!proxy.isCurrentClient(this)) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Keep-Alive, " + toString(true));
			}
			return;
		}
		if (chunked) {
			response.endRead();
			response.write(proxyHandler, false, chunked);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost and Proxy Responded a Final Chunk, " +
						toString(true));
			}
			reset();
			return;
		}
		if (logLevel >= LOG_DEBUG) {
			if (!begun) {
				Log.d((established ? "Incomplete Header, " :
						"Client Not Connected, ") + toString(true));
			} else if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Response, " + toString(true));
			}
		}
		// TODO if (!begun) retry request 
		if (!established) {
			proxyHandler.send(ProxyConnection.GATEWAY_TIMEOUT);
		}
		proxy.disconnect();
	}

	void sendRequest(boolean begin) {
		request.write(handler, begin, false);
		if (!request.isComplete()) {
			return;
		}
		if (logLevel >= LOG_VERBOSE) {
			Log.v("Request Sent, " + toString(false));
		}
		if (response.isComplete()) {
			reset();
		}
	}

	private void sendResponse(boolean begin) {
		response.write(proxyHandler, begin, chunked);
		if (!response.isComplete()) {
			return;
		}
		if (requestClose) {
			proxy.disconnect();
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				Log.v("Proxy Connection Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Request, " + toString(false));
			}
			return;
		}
		if (responseClose) {
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				Log.v("Response Sent and Client Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Response, " + toString(true));
			}
			handler.disconnect();
			remove();
		} else {
			handler.setBufferSize(MAX_BUFFER_SIZE);
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				Log.v("Response Sent and Unblocked, " + toString(true));
			}
		}
		if (request.isComplete()) {
			reset();
		}
	}

	private void reset() {
		request.reset();
		response.reset();
		proxyHandler.setBufferSize(MAX_BUFFER_SIZE);
		if (logLevel >= ProxyConnection.LOG_VERBOSE) {
			Log.v((proxy.getClientMap(secure).get(host) == null ? "Client Closed" :
					"Client Kept Alive") + " and Request Unblocked due to " +
					"Complete Request and Response, " + toString(false));
		}
		proxy.clearCurrentClient();
		proxy.read();
	}
}

public class ProxyConnection implements Connection {
	public static final int LOG_NONE = 0;
	public static final int LOG_DEBUG = 1;
	public static final int LOG_VERBOSE = 2;

	private static final String ERROR_HEADERS = "\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: close\r\n\r\n";
	private static final byte[] BAD_REQUEST =
			("HTTP/1.1 400 Bad Request" + ERROR_HEADERS).getBytes();
	private static final byte[] REQUEST_ENTITY_TOO_LARGE =
			("HTTP/1.1 413 Request Entity Too Large" + ERROR_HEADERS).getBytes();
	private static final byte[] NOT_IMPLEMENTED =
			("HTTP/1.1 501 Not Implemented" + ERROR_HEADERS).getBytes();
	static final byte[] BAD_GATEWAY =
			("HTTP/1.1 502 Bad Gateway" + ERROR_HEADERS).getBytes();
	static final byte[] GATEWAY_TIMEOUT =
			("HTTP/1.1 504 Gateway Timeout" + ERROR_HEADERS).getBytes();

	private Connector connector;
	private Executor executor;
	private SSLContext sslc;
	private BiPredicate<String, String> auth;
	private String realm;
	private int logLevel;
	private ConnectionHandler handler;
	private ByteArrayQueue queue = new ByteArrayQueue();
	private HttpPacket request = new HttpPacket();
	private PeerConnection peer = null;
	private ClientConnection client = null;
	private HashMap<String, ClientConnection> clientMap = new HashMap<>();
	private HashMap<String, ClientConnection> secureClientMap = new HashMap<>();

	boolean isCurrentClient(ClientConnection client_) {
		return client == client_;
	}

	void clearCurrentClient() {
		client = null;
	}

	HashMap<String, ClientConnection> getClientMap(boolean secure) {
		return secure ? secureClientMap : clientMap;
	}

	String getRemote() {
		return handler.getRemoteAddr() + ":" + handler.getRemotePort();
	}

	private void readEx() throws HttpPacketException {
		request.read(queue);
		if (client != null) {
			client.sendRequest(false);
			return;
		}
		if (!request.isCompleteHeader()) {
			return;
		}

		boolean connectionClose = request.isHttp10() ||
				request.testHeader("CONNECTION", "close", true, true) ||
				request.testHeader("PROXY-CONNECTION", "close", true, true);
		if (auth != null) {
			boolean authenticated = false;
			String proxyAuth = request.getHeader("PROXY-AUTHORIZATION");
			if (proxyAuth != null && proxyAuth.toUpperCase().startsWith("BASIC ")) {
				String basic = new String(Base64.getDecoder().decode(proxyAuth.substring(6)));
				int colon = basic.indexOf(':');
				if (colon >= 0) {
					authenticated = auth.test(basic.substring(0, colon),
							basic.substring(colon + 1));
				}
			}
			if (!authenticated) {
				HttpPacket response = new HttpPacket();
				response.setType(HttpPacket.TYPE_RESPONSE);
				response.setStatus(407);
				response.setMessage("Proxy Authentication Required");
				response.setHeader("Proxy-Authenticate", realm == null ||
						realm.isEmpty() ? "Basic" : "Basic realm=\"" + realm + "\"");
				response.setHeader("Content-Length", "0");
				if (connectionClose || !request.isComplete()) {
					// Skip reading body
					response.setHeader("Connection", "close");
					response.write(handler, true, false);
					handler.disconnect();
				} else { 
					response.setHeader("Connection", "keep-alive");
					response.write(handler, true, false);
					request.reset();
					// No request from peer, so continue reading
					if (queue.length() > 0) {
						readEx();
					}
				}
				if (logLevel >= LOG_DEBUG) {
					Log.d("Auth Failed, " + getRemote());
				}
				return;
			}
		}

		String method = request.getMethod().toUpperCase();
		if (method.equals("CONNECT")) {
			String path = request.getPath();
			int colon = path.lastIndexOf(':');
			if (colon < 0) {
				throw new HttpPacketException("Invalid Destination", path);
			}
			String host = path.substring(0, colon);
			String value = path.substring(colon + 1);
			int port;
			try {
				port = Integer.parseInt(value);
			} catch (NumberFormatException e) {
				port = -1;
			}
			if (port < 0 || port > 0xFFFF) {
				throw new HttpPacketException("Invalid Port", value);
			}
			peer = new PeerConnection(this, handler, logLevel, host, port);
			try {
				connector.connect(peer, host, port);
			} catch (IOException e) {
				throw new HttpPacketException("Invalid Host", host);
			}
			ByteArrayQueue body = request.getBody();
			if (body.length() > 0) {
				peer.getHandler().send(body.array(), body.offset(), body.length());
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Connection Launched, " + peer.toString(false));
			}
			return;
		}

		String path = request.getPath();
		boolean secure = false;
		String host, connectHost;
		int port;
		try {
			// Use "host:port" in path
			URL url = new URL(path);
			String proto = url.getProtocol().toLowerCase();
			if (proto.equals("https")) {
				secure = true;
			} else if (!proto.equals("http")) {
				if (logLevel >= LOG_DEBUG) {
					Log.d("Unable to Implement \"" + proto + "\", " + getRemote());
				}
				handler.send(NOT_IMPLEMENTED);
				disconnect();
				return;
			}
			connectHost = url.getHost();
			port = url.getPort();
			if (port < 0) {
				host = connectHost;
				port = url.getDefaultPort();
			} else {
				host = connectHost + ":" + port;
			}
			port = port < 0 ? url.getDefaultPort() : port;
			String query = url.getQuery();
			path = url.getPath();
			path = (path == null || path.isEmpty() ? "/" : path) +
					(query == null || query.isEmpty() ? "" : "?" + query);
		} catch (IOException e) {
			// Use "Host" in headers if "host:port" not in path
			host = request.getHeader("HOST");
			if (host == null) {
				throw new HttpPacketException("Invalid Host", "");
			}
			int colon = host.lastIndexOf(':');
			if (colon < 0) {
				connectHost = host;
				port = 80;
			} else {
				connectHost = host.substring(0, colon);
				String value = host.substring(colon + 1);
				try {
					port = Integer.parseInt(value);
				} catch (NumberFormatException e_) {
					port = -1;
				}
				if (port < 0 || port > 0xFFFF) {
					throw new HttpPacketException("Invalid Port", value);
				}
			}
		}
		request.setPath(path);
		if (request.getHeader("HOST") == null) {
			request.setHeader("Host", host);
		}
		request.removeHeader("PROXY-AUTHORIZATION");
		request.removeHeader("PROXY-CONNECTION");

		client = getClientMap(secure).get(host);
		if (client == null) {
			client = new ClientConnection(this, handler, logLevel, request, secure, host);
			getClientMap(secure).put(host, client);
			Connection connection;
			if (secure) {
				connection = client.appendFilter(new SSLFilter(executor,
						sslc, SSLFilter.CLIENT, connectHost, port));
			} else {
				connection = client;
			}
			try {
				connector.connect(connection, connectHost, port);
			} catch (IOException e) {
				throw new HttpPacketException("Invalid Host", connectHost);
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Created, " + client.toString(false));
			}
		} else if (logLevel >= LOG_VERBOSE) {
			Log.v("Client Reused, " + client.toString(false));
		}
		client.begin(method.equals("HEAD"), connectionClose);
	}

	void read() {
		if (queue.length() == 0) {
			return;
		}
		try {
			readEx();
		} catch (HttpPacketException e) {
			if (logLevel >= LOG_DEBUG) {
				Log.d(e.getMessage() + ", " + getRemote());
			}
			if (client == null || !client.isBegun()) {
				handler.send(e.getType() == HttpPacketException.HEADER_SIZE ||
						e.getType() == HttpPacketException.HEADER_COUNT ?
						REQUEST_ENTITY_TOO_LARGE : BAD_REQUEST);
			}
			disconnect();
		}
	}

	void disconnect() {
		for (ClientConnection client_ : clientMap.values()) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Closed, " + client_.toString(false));
			}
			client_.getHandler().disconnect();
		}
		for (ClientConnection client_ : secureClientMap.values()) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Closed, " + client_.toString(false));
			}
			client_.getHandler().disconnect();
		}
		handler.disconnect();
	}

	public ProxyConnection(Connector connector, Executor executor, SSLContext sslc,
			BiPredicate<String, String> auth, String realm, int logLevel) {
		this.connector = connector;
		this.executor = executor;
		this.sslc = sslc;
		this.auth = auth;
		this.realm = realm;
		this.logLevel = logLevel;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer != null) {
			peer.getHandler().send(b, off, len);
			return;
		}

		queue.add(b, off, len);
		if (request.isComplete()) {
			handler.setBufferSize(0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Request Blocked due to Complete Request but Incomplete Response, " +
						(client == null ? getRemote() : client.toString(false)));
			}
		} else {
			read();
		}
	}

	@Override
	public void onQueue(int delta, int total) {
		if (peer != null) {
			peer.getHandler().setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v((total == 0 ? "Connection Unblocked, " :
						"Connection Blocked, ") + peer.toString(true));
			}
		} else if (client != null) {
			client.getHandler().setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v((total == 0 ? "Response Unblocked, " :
						"Response Blocked, ") + client.toString(true));
			}
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			peer.getHandler().disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Connection Closed, " + peer.toString(false));
			}
		}
		disconnect();
	}
}