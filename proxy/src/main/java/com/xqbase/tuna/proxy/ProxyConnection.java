package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.ByteArrayQueue;

/** Connection for <b>CONNECT</b> */
class PeerConnection implements Connection {
	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	/** <code>null</code> for an established connection */
	private ByteArrayQueue queue;

	ProxyConnection peer;
	ConnectionHandler handler;

	PeerConnection(ProxyConnection peer, ByteArrayQueue body) {
		this.peer = peer;
		handler.send(body.array(), body.offset(), body.length());
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onConnect() {
		peer.handler.send(CONNECTION_ESTABLISHED);
		queue = null;
	}

	@Override
	public void onQueue(int delta, int total) {
		peer.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		peer.handler.send(b, off, len);
	}

	@Override
	public void onDisconnect() {
		if (queue == null) {
			peer.handler.disconnect();
		} else {
			peer.sendError(503);
		}
	}
}

/** Connection for request except <b>CONNECT</b> */
class ClientConnection implements Connection {
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] COLON = {':', ' '};
	private static final byte[] SPACE = {' '};
	private static final byte[] FINAL_CRLF = {'0', '\r', '\n'};
	private static final byte[] HTTP11 = " HTTP/1.1\r\n".getBytes();

	private HttpPacket request, response = null;
	private String host, path;

	ProxyConnection peer;
	ConnectionHandler handler;

	ClientConnection(ProxyConnection peer, HttpPacket request, String host, String path) {
		this.peer = peer;
		this.request = request;
		this.host = host;
		this.path = path;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onConnect() {
		send(true);
	}

	@Override
	public void onQueue(int delta, int total) {
		peer.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		peer.handler.send(b, off, len);
	}

	@Override
	public void onDisconnect() {
		peer.handler.disconnect();
	}

	private void writeHeaders(ByteArrayQueue data) {
		Iterator<Map.Entry<String, ArrayList<String[]>>> it =
				request.getHeaders().entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ArrayList<String[]>> entry = it.next();
			it.remove();
			String key = entry.getKey();
			boolean reserved = false;
			switch (key) {
			case "CONNECTION":
			case "PROXY-CONNECTION":
			}
			if (reserved) {
				continue;
			}
			for (String[] header : entry.getValue()) {
				data.add(header[0].getBytes()).add(COLON).
						add(header[1].getBytes()).add(CRLF);
			}
		}
	}

	void send(boolean begin) {
		ByteArrayQueue data = new ByteArrayQueue();
		if (begin) {
			data.add(request.getMethod().getBytes()).
					add(SPACE).add(path.getBytes()).add(HTTP11);
			writeHeaders(data);
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
				writeHeaders(data);
				data.add(CRLF);
			}
		} else {
			data.add(body.array(), body.offset(), body.length());
		}
		if (body.length() > 0) {
			if (request.isChunked()) {
				data.add(Integer.toHexString(body.length()).getBytes());
				data.add(CRLF);
				data.add(body.array(), body.offset(), body.length());
				data.add(CRLF);
			} else {
				data.add(body.array(), body.offset(), body.length());
			}
		}


		handler.send(data.array(), data.offset(), data.length());
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
	private static final int SC_BAD_REQUEST = 400;
	private static final int SC_INTERNAL_SERVER_ERROR = 500;
	private static final int SC_SERVICE_UNAVAILABLE = 503;
	private static final int[] ERROR_STATUS = {
		SC_BAD_REQUEST, SC_INTERNAL_SERVER_ERROR, SC_SERVICE_UNAVAILABLE,
	};
	private static final String[] ERROR_MESSAGE = {
		"Bad Request", "Internal Server Error", "Service Unavailable",
	};

	private static HashMap<Integer, String> errorMap = new HashMap<>();

	static {
		for (int i = 0; i < ERROR_STATUS.length; i ++) {
			errorMap.put(Integer.valueOf(ERROR_STATUS[i]), ERROR_MESSAGE[i]);
		}
	}

	ConnectionHandler handler = null;

	void sendError(int status) {
		handler.send(("HTTP/1.1 " + status + " " +
				errorMap.get(Integer.valueOf(status)) + "\r\n" +
				"Content-Length: 0\r\n" +
				"Connection: close\r\n\r\n").getBytes());
		handler.disconnect();
	}

	private Connector connector;
	private Executor executor;
	private BiPredicate<String, String> auth;
	private HttpPacket packet = new HttpPacket(HttpPacket.Type.REQUEST);
	private HashMap<String, ClientConnection> clientMap = new HashMap<>();
	private HashMap<String, SSLContext> sslMap = new HashMap<>();
	private ClientConnection client = null;
	private PeerConnection peer = null;

	public ProxyConnection(Connector connector, Executor executor,
			BiPredicate<String, String> auth) {
		this.connector = connector;
		this.executor = executor;
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

		int bytesRead = 0;
		while (bytesRead < len) {
			int n = packet.read(b, off + bytesRead, len - bytesRead);
			if (n < 0) {
				sendError(SC_BAD_REQUEST);
				return;
			}
			bytesRead += n;

			if (client != null) {
				client.send(false);
				if (packet.isComplete()) {
					continue;
				}
				if (bytesRead < len) {
					sendError(SC_INTERNAL_SERVER_ERROR);
				}
				return;
			}

			if (!packet.isCompleteHeader()) {
				if (bytesRead < len) {
					sendError(SC_INTERNAL_SERVER_ERROR);
				}
				return;
			}

			if (auth != null) {
				boolean authenticated = false;
				LinkedHashMap<String, ArrayList<String[]>> headers = packet.getHeaders();
				ArrayList<String[]> values = headers.get("PROXY-AUTHORIZATION");
				if (values.size() == 1) {
					String value = values.get(0)[1];
					if (value.toUpperCase().startsWith("BASIC ")) {
						String basic = new String(Base64.getDecoder().
								decode(value.substring(6)));
						int colon = basic.indexOf(':');
						if (colon >= 0) {
							authenticated = auth.test(basic.substring(0, colon),
									basic.substring(colon + 1));
						}
					}
				}
				if (!authenticated) {
					if (packet.isComplete()) {
						handler.send(AUTH_REQUIRED_KEEP_ALIVE);
						packet = new HttpPacket(HttpPacket.Type.REQUEST);
						continue;
					}
					handler.send(AUTH_REQUIRED_CLOSE);
					handler.disconnect();
					return;
				}
			}

			String method = packet.getMethod().toUpperCase();
			if (method.equals("CONNECT")) {
				if (bytesRead < len) {
					sendError(SC_INTERNAL_SERVER_ERROR);
					return;
				}
				String path = packet.getPath();
				int colon = path.lastIndexOf(':');
				if (colon < 0) {
					sendError(SC_BAD_REQUEST);
					return;
				}
				String host = path.substring(0, colon);
				int port;
				try {
					port = Integer.parseInt(path.substring(colon + 1));
				} catch (NumberFormatException e) {
					sendError(SC_BAD_REQUEST);
					return;
				}
				peer = new PeerConnection(this, packet.getBody());
				try {
					connector.connect(peer, host, port);
				} catch (IOException e) {
					sendError(SC_BAD_REQUEST);
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
					sendError(SC_BAD_REQUEST);
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
				// "host:port" not in path, use "Host" in headers
				ArrayList<String[]> values = packet.getHeaders().get("HOST");
				if (values == null || values.size() != 1) {
					sendError(SC_BAD_REQUEST);
					return;
				}
				host = values.get(0)[1];
				int colon = host.lastIndexOf(':');
				if (colon < 0) {
					connectHost = host;
					port = 80;
				} else {
					connectHost = host.substring(0, colon);
					try {
						port = Integer.parseInt(host.substring(colon + 1));
					} catch (NumberFormatException e_) {
						sendError(SC_BAD_REQUEST);
						return;
					}
				}
			}
			// TODO reuse client, by host/port/secure
			client = new ClientConnection(this, packet, host, path);
			Connection connection;
			if (secure) {
				// TODO Build and reuse SSLContext
				SSLContext sslc = sslMap.get(host);
				connection = client.appendFilter(new SSLFilter(executor, sslc, SSLFilter.CLIENT)); 
			} else {
				connection = client;
			}
			// TODO reuse client
			try {
				connector.connect(connection, connectHost, port);
			} catch (IOException e) {
				sendError(SC_BAD_REQUEST);
			}
		}
	}

	@Override
	public void onQueue(int delta, int total) {
		if (peer != null) {
			peer.handler.setBufferSize(total == 0 ? MAX_BUFFER_SIZE : 0);
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			peer.handler.disconnect();
		}
	}
}