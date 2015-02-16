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
	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	private ProxyConnection peer;
	private ConnectionHandler peerHandler;
	private boolean established = false;
	// For DEBUG only
	private String host;
	private int port;
	private boolean debug, verbose;

	ConnectionHandler handler;

	PeerConnection(ProxyConnection peer, ConnectionHandler peerHandler,
			String host, int port, boolean debug, boolean verbose) {
		this.peer = peer;
		this.peerHandler = peerHandler;
		this.host = host;
		this.port = port;
		this.debug = debug;
		this.verbose = verbose;
		if (verbose) {
			Log.v("Connection Launched, " + toString(false));
		}
	}

	String toString(boolean recv) {
		return peerHandler.getRemoteAddr() + ":" + peerHandler.getRemotePort() +
				(recv ? " <= " : " => ") + host + ":" + port;
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
		if (verbose) {
			Log.v((total == 0 ? "Connection Unblocked, " : "Connection Blocked, ") +
					toString(false));
		}
		peerHandler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
	}

	@Override
	public void onConnect() {
		if (verbose) {
			Log.v("Connection Established, " + toString(false));
		}
		peerHandler.send(CONNECTION_ESTABLISHED);
		established = true;
	}

	@Override
	public void onDisconnect() {
		if (!established) {
			if (debug) {
				Log.d("Connection Failed, " + toString(false));
			}
			peer.gatewayTimeout();
		} else if (verbose) {
			Log.v("Connection Lost, " + toString(true));
		}
		peer.disconnect();
	}
}

/** Connection for request except <b>CONNECT</b> */
class ClientConnection implements Connection {
	private ProxyConnection peer;
	private ConnectionHandler peerHandler;
	private HttpPacket request, response = new HttpPacket();
	private boolean secure, begun = false, connectionClose = false, debug, verbose;
	private String host;
	private ByteArrayQueue queue = new ByteArrayQueue();

	ConnectionHandler handler;

	ClientConnection(ProxyConnection peer, ConnectionHandler peerHandler,
			HttpPacket request, boolean secure, String host, boolean debug, boolean verbose) {
		this.peer = peer;
		this.peerHandler = peerHandler;
		this.request = request;
		this.secure = secure;
		this.host = host;
		this.debug = debug;
		this.verbose = verbose;
	}

	String toString(boolean recv) {
		String path = request.getPath();
		return peerHandler.getRemoteAddr() + ":" + peerHandler.getRemotePort() +
				(recv ? " <= " : " => ") + (secure ? "https://" : "http://") +
				host + (path == null ? "" : path);
	}

	void begin(String path, boolean head, boolean connectionClose_) {
		begun = false;
		request.setPath(path);
		request.removeHeader("PROXY-AUTHORIZATION");
		request.removeHeader("PROXY-CONNECTION");
		request.setHeader("Connection", "keep-alive");
		response.setType(head ? HttpPacket.TYPE_RESPONSE_FOR_HEAD : HttpPacket.TYPE_RESPONSE);
		connectionClose = connectionClose_;
		sendRequest(true);
	}

	boolean isComplete() {
		return response != null && response.isComplete();
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (this != peer.getClient()) {
			if (debug) {
				Log.d("Unexpected Response: \"" + new String(b, off, len) + "\", " +
						toString(true));
			}
			handler.disconnect();
			onDisconnect();
			return;
		}
		queue.add(b, off, len);
		try {
			response.read(queue);
		} catch (HttpPacketException e) {
			if (debug) {
				Log.d(e.getMessage() + ", " + toString(true));
			}
			// Disconnect Peer for a Bad Response
			handler.disconnect();
			onDisconnect();
			return;
		}
		if (begun) {
			sendResponse(false);
		} else if (response.isCompleteHeader()) {
			if (verbose) {
				Log.v("Response Header Received, " + toString(true));
			}
			begun = true;
			response.removeHeader("PROXY-CONNECTION");
			response.setHeader("Connection", connectionClose ? "close" : "keep-alive");
			sendResponse(true);
		}
	}

	@Override
	public void onQueue(int delta, int total) {
		if (verbose) {
			Log.v((total == 0 ? "Request Unblocked, " : "Request Blocked, ") +
					toString(false));
		}
		peerHandler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
	}

	@Override
	public void onDisconnect() {
		peer.getClientMap(secure).remove(host);
		if (response.isComplete()) {
			if (verbose) {
				Log.v("Client Lost, " + toString(true));
			}
			response.reset();
			return;
		}
		if (!begun && !response.isCompleteHeader()) {
			if (debug) {
				Log.d("Incomplete Header, " + toString(true));
			}
			peer.badGateway();
		}
		peerHandler.disconnect();
	}

	void sendRequest(boolean begin) {
		ByteArrayQueue data = new ByteArrayQueue();
		request.write(data, false, begin);
		handler.send(data.array(), data.offset(), data.length());
		if (request.isComplete()) {
			if (verbose) {
				Log.v("Request Sent, " + toString(false));
			}
			request.reset();
		}
	}

	private void sendResponse(boolean begin) {
		ByteArrayQueue data = new ByteArrayQueue();
		response.write(data, request.isHttp10(), begin);
		if (request.isHttp10()) {
			peerHandler.send(data.array(), data.offset(), data.length());
			if (response.isComplete()) {
				handler.disconnect();
				onDisconnect();
			}
			return;
		}
		peerHandler.send(data.array(), data.offset(), data.length());
		if (!response.isComplete()) {
			return;
		}
		if (connectionClose) {
			if (verbose) {
				Log.v("Client Closed due to HTTP/1.0 or " +
						"\"Connection: close\", " + toString(true));
			}
			handler.disconnect();
			onDisconnect();
		} else {
			response.reset();
			peer.setClient(null);
			// Unblock Next Request if Response Completed
			if (verbose) {
				Log.v("Request Unblocked due to Complete Response, " + toString(false));
			}
			peerHandler.setBufferSize(MAX_BUFFER_SIZE);
			peer.read();
		}
	}
}

public class ProxyConnection implements Connection {
	private static final byte[] AUTH_REQUIRED_KEEP_ALIVE =
			("HTTP/1.1 407 Proxy Authentication Required\r\n" +
			"Proxy-Authenticate: Basic\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: keep-alive\r\n\r\n").getBytes();
	private static final byte[] AUTH_REQUIRED_CLOSE =
			("HTTP/1.1 407 Proxy Authentication Required\r\n" +
			"Proxy-Authenticate: Basic\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: close\r\n\r\n").getBytes();
	private static final byte[] BAD_REQUEST =
			("HTTP/1.1 400 Bad Request\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: close\r\n\r\n").getBytes();
	private static final byte[] BAD_GATEWAY =
			("HTTP/1.1 502 Bad Gateway\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: close\r\n\r\n").getBytes();
	private static final byte[] GATEWAY_TIMEOUT =
			("HTTP/1.1 504 Gateway Timeout\r\n" +
			"Content-Length: 0\r\n" +
			"Connection: close\r\n\r\n").getBytes();

	private Connector connector;
	private Executor executor;
	private SSLContext sslc;
	private BiPredicate<String, String> auth;
	private boolean debug, verbose;
	private ByteArrayQueue queue = new ByteArrayQueue();
	private HttpPacket packet = new HttpPacket();
	private PeerConnection peer = null;
	private ConnectionHandler handler = null;
	private ClientConnection client = null;
	private HashMap<String, ClientConnection> clientMap = new HashMap<>();
	private HashMap<String, ClientConnection> secureClientMap = new HashMap<>();

	void badGateway() {
		handler.send(BAD_GATEWAY);
	}

	void gatewayTimeout() {
		handler.send(GATEWAY_TIMEOUT);
	}

	ClientConnection getClient() {
		return client;
	}

	void setClient(ClientConnection client) {
		this.client = client;
	}

	HashMap<String, ClientConnection> getClientMap(boolean secure) {
		return secure ? secureClientMap : clientMap;
	}

	private String getRemoteInfo() {
		return handler.getRemoteAddr() + ":" + handler.getRemotePort();
	}

	private void readEx() throws HttpPacketException {
		packet.read(queue);
		if (client != null) {
			client.sendRequest(false);
			return;
		}
		if (!packet.isCompleteHeader()) {
			return;
		}

		boolean connectionClose = packet.isHttp10() ||
				packet.testHeader("CONNECTION", "close", true) ||
				packet.testHeader("PROXY-CONNECTION", "close", true);
		if (auth != null) {
			boolean authenticated = false;
			String proxyAuth = packet.getHeader("PROXY-AUTHORIZATION");
			if (proxyAuth != null && proxyAuth.toUpperCase().startsWith("BASIC ")) {
				String basic = new String(Base64.getDecoder().decode(proxyAuth.substring(6)));
				int colon = basic.indexOf(':');
				if (colon >= 0) {
					authenticated = auth.test(basic.substring(0, colon),
							basic.substring(colon + 1));
				}
			}
			if (!authenticated) {
				if (connectionClose || !packet.isComplete()) {
					// Skip reading body
					handler.send(AUTH_REQUIRED_CLOSE);
					handler.disconnect();
				} else { 
					handler.send(AUTH_REQUIRED_KEEP_ALIVE);
					packet.reset();
					// No request from peer, so continue reading
					if (queue.length() > 0) {
						readEx();
					}
				}
				Log.d("Auth Failed from " + handler.getRemoteAddr());
				return;
			}
		}

		String method = packet.getMethod().toUpperCase();
		if (method.equals("CONNECT")) {
			String path = packet.getPath();
			int colon = path.lastIndexOf(':');
			if (colon < 0) {
				throw new HttpPacketException(HttpPacketException.Type.DESTINATION, path);
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
				throw new HttpPacketException(HttpPacketException.Type.PORT, value);
			}
			peer = new PeerConnection(this, handler, host, port, debug, verbose);
			try {
				connector.connect(peer, host, port);
			} catch (IOException e) {
				throw new HttpPacketException(HttpPacketException.Type.HOST, host);
			}
			ByteArrayQueue body = packet.getBody();
			if (body.length() > 0) {
				peer.handler.send(body.array(), body.offset(), body.length());
			}
			return;
		}

		String path = packet.getPath();
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
				throw new HttpPacketException(HttpPacketException.Type.PROTOCOL, proto);
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
			host = packet.getHeader("HOST");
			if (host == null) {
				throw new HttpPacketException(HttpPacketException.Type.HOST, "");
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
					throw new HttpPacketException(HttpPacketException.Type.PORT, value);
				}
			}
		}

		client = getClientMap(secure).get(host);
		boolean created = false;
		if (client == null) {
			client = new ClientConnection(this, handler, packet, secure, host, debug, verbose);
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
				throw new HttpPacketException(HttpPacketException.Type.HOST, connectHost);
			}
			created = true;
		} else if (verbose) {
			Log.v("Client Reused, " + client.toString(false));
		}
		client.begin(path, method.equals("HEAD"), connectionClose);
		if (verbose) {
			Log.v((created ? "Client Created, " : "Client Reused, ") + client.toString(false));
		}
	}

	void read() {
		if (queue.length() == 0) {
			return;
		}
		try {
			readEx();
		} catch (HttpPacketException e) {
			if (debug) {
				Log.d(e.getMessage() + ", " + getRemoteInfo());
			}
			handler.send(BAD_REQUEST);
			disconnect();
		}
		// Block Next Request if Request Completed but Response not yet
		if (packet.isComplete() && client != null && !client.isComplete()) {
			if (verbose) {
				Log.v("Request Blocked due to Incomplete Response, " +
						client.toString(false));
			}
			handler.setBufferSize(0);
		}
	}

	void disconnect() {
		for (ClientConnection client_ : clientMap.values()) {
			Log.v("Client Closed, " + client_.toString(false));
			client_.handler.disconnect();
		}
		for (ClientConnection client_ : secureClientMap.values()) {
			Log.v("Client Closed, " + client_.toString(false));
			client_.handler.disconnect();
		}
		handler.disconnect();
	}

	public ProxyConnection(Connector connector, Executor executor,
			SSLContext sslc, BiPredicate<String, String> auth,
			boolean debug, boolean verbose) {
		this.connector = connector;
		this.executor = executor;
		this.sslc = sslc;
		this.auth = auth;
		this.debug = debug;
		this.verbose = verbose;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer != null) {
			peer.handler.send(b, off, len);
			return;
		}

		queue.add(b, off, len);
		read();
	}

	@Override
	public void onQueue(int delta, int total) {
		if (peer != null) {
			if (verbose) {
				Log.v((total == 0 ? "Connection Unblocked, " : "Connection Blocked, ") +
						peer.toString(true));
			}
			peer.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		} else if (client != null) {
			if (verbose) {
				Log.v((total == 0 ? "Response Unblocked, " : "Response Blocked, ") +
						client.toString(true));
			}
			client.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			if (verbose) {
				Log.v("Connection Closed, " + peer.toString(false));
			}
			peer.handler.disconnect();
		}
		disconnect();
	}
}