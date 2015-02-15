package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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

/** Connection for <b>CONNECT</b> */
class PeerConnection implements Connection {
	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	private ProxyConnection peer;
	private ConnectionHandler peerHandler;
	private boolean established = false;

	ConnectionHandler handler;

	PeerConnection(ProxyConnection peer, ConnectionHandler peerHandler) {
		this.peer = peer;
		this.peerHandler = peerHandler;
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
	}

	@Override
	public void onConnect() {
		peerHandler.send(CONNECTION_ESTABLISHED);
		established = true;
	}

	@Override
	public void onDisconnect() {
		if (!established) {
			peer.gatewayTimeout();
		}
		peer.disconnect();
	}
}

/** Connection for request except <b>CONNECT</b> */
class ClientConnection implements Connection {
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] COLON = {':', ' '};
	private static final byte[] SPACE = {' '};
	private static final byte[] FINAL_CRLF = {'0', '\r', '\n'};
	private static final byte[] HTTP10 = "HTTP/1.0".getBytes();
	private static final byte[] HTTP11 = "HTTP/1.1".getBytes();

	private static void writeHeaders(ByteArrayQueue data, HttpPacket packet) {
		Iterator<Map.Entry<String, ArrayList<String>>> it =
				packet.getHeaders().entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ArrayList<String>> entry = it.next();
			it.remove();
			ArrayList<String> values = entry.getValue();
			int size = values.size();
			if (size < 1) {
				continue;
			}
			byte[] keyBytes = values.get(0).getBytes();
			for (int i = 1; i < size; i ++) {
				data.add(keyBytes).add(COLON).
						add(values.get(i).getBytes()).add(CRLF);
			}
		}
		data.add(CRLF);
	}

	private ProxyConnection peer;
	private ConnectionHandler peerHandler;
	private HttpPacket request, response = new HttpPacket();
	private boolean secure, head = false, connectionClose = false;
	private String host, path = null;
	private ByteArrayQueue queue = new ByteArrayQueue();

	ConnectionHandler handler;

	ClientConnection(ProxyConnection peer, ConnectionHandler peerHandler,
			HttpPacket request, boolean secure, String host) {
		this.peer = peer;
		this.peerHandler = peerHandler;
		this.request = request;
		this.secure = secure;
		this.host = host;
	}

	void init(String path_, boolean head_, boolean connectionClose_) {
		path = path_;
		head = head_;
		connectionClose = connectionClose_;
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
			// Should not receive data here
			handler.disconnect();
			onDisconnect();
			return;
		}
		queue.add(b, off, len);
		try {
			response.read(queue);
		} catch (HttpPacketException e) {
			// Disconnect Peer for a Bad Response
			handler.disconnect();
			onDisconnect();
			return;
		}
		if (path == null) {
			sendResponse(false);
		} else if (response.isCompleteHeader()) {
			path = null;
			sendResponse(true);
		}
	}

	@Override
	public void onQueue(int delta, int total) {
		peerHandler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
	}

	@Override
	public void onConnect() {
		response.setType(head ? HttpPacket.Type.RESPONSE_FOR_HEAD : HttpPacket.Type.RESPONSE);
	}

	@Override
	public void onDisconnect() {
		peer.getClientMap(secure).remove(host);
		if (response.isComplete()) {
			response.reset();
			return;
		}
		if (path != null && !response.isCompleteHeader()) {
			peer.badGateway();
		}
		peerHandler.disconnect();
	}

	void sendRequest(boolean begin) {
		ByteArrayQueue data = new ByteArrayQueue();
		if (begin) {
			data.add(request.getMethod().getBytes()).add(SPACE).
					add(path.getBytes()).add(SPACE).
					add(HTTP11).add(CRLF);
			request.removeHeader("PROXY-AUTHORIZATION");
			request.removeHeader("PROXY-CONNECTION");
			request.setHeader("Connection", "keep-alive");
			writeHeaders(data, request);
		}
		ByteArrayQueue body = request.getBody();
		if (request.isChunked()) {
			if (body.length() > 0) {
				data.add(Integer.toHexString(body.length()).getBytes());
				data.add(CRLF);
				data.add(body.array(), body.offset(), body.length());
				data.add(CRLF);
			}
			if (request.isComplete()) {
				data.add(FINAL_CRLF);
				writeHeaders(data, request);
			}
		} else if (body.length() > 0) {
			data.add(body.array(), body.offset(), body.length());
		}
		handler.send(data.array(), data.offset(), data.length());
		if (!request.isComplete()) {
			return;
		}
		request.reset();
	}

	private void sendResponse(boolean begin) {
		ByteArrayQueue data = new ByteArrayQueue();
		if (begin) {
			data.add(request.isHttp10() ? HTTP10 : HTTP11).add(SPACE).
					add(("" + response.getStatus()).getBytes()).add(SPACE).
					add(response.getMessage().getBytes()).add(CRLF);
			writeHeaders(data, response);
		}
		ByteArrayQueue body = response.getBody();
		if (request.isHttp10()) {
			if (body.length() > 0) {
				data.add(body.array(), body.offset(), body.length());
			}
			peerHandler.send(data.array(), data.offset(), data.length());
			if (response.isComplete()) {
				handler.disconnect();
				onDisconnect();
			}
			return;
		}
		if (response.isChunked()) {
			if (body.length() > 0) {
				data.add(Integer.toHexString(body.length()).getBytes());
				data.add(CRLF);
				data.add(body.array(), body.offset(), body.length());
				data.add(CRLF);
			}
			if (response.isComplete()) {
				data.add(FINAL_CRLF);
				writeHeaders(data, response);
			}
		} else if (body.length() > 0) {
			data.add(body.array(), body.offset(), body.length());
		}
		peerHandler.send(data.array(), data.offset(), data.length());
		if (!response.isComplete()) {
			return;
		}
		if (connectionClose) {
			handler.disconnect();
			onDisconnect();
		} else {
			response.reset();
			peer.setClient(null);
			// Unblock Next Request if Response Completed
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
			peer = new PeerConnection(this, handler);
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
		if (client == null) {
			client = new ClientConnection(this, handler, packet, secure, host);
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
		}
		client.init(path, method.equals("HEAD"), connectionClose);
		client.sendRequest(true);
	}

	void read() {
		if (queue.length() == 0) {
			return;
		}
		try {
			readEx();
		} catch (HttpPacketException e) {
			handler.send(BAD_REQUEST);
			disconnect();
		}
		// Block Next Request if Request Completed but Response not yet
		if (packet.isComplete() && client != null && !client.isComplete()) {
			handler.setBufferSize(0);
		}
	}

	void disconnect() {
		for (ClientConnection client_ : clientMap.values()) {
			client_.handler.disconnect();
		}
		for (ClientConnection client_ : secureClientMap.values()) {
			client_.handler.disconnect();
		}
		handler.disconnect();
	}

	public ProxyConnection(Connector connector, Executor executor,
			SSLContext sslc, BiPredicate<String, String> auth) {
		this.connector = connector;
		this.executor = executor;
		this.sslc = sslc;
		this.auth = auth;
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
			peer.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		} else if (client != null) {
			client.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			peer.handler.disconnect();
		}
		disconnect();
	}
}