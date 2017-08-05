package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLSession;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.http.HttpPacketException;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.tuna.ssl.SSLConnectionSession;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.tuna.util.Expirable;
import com.xqbase.tuna.util.LinkedEntry;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;

public class ProxyConnection
		implements Connection, Expirable<ProxyConnection>, HttpStatus {
	public static final int FORWARDED_TRANSPARENT = 0;
	public static final int FORWARDED_DELETE = 1;
	public static final int FORWARDED_OFF = 2;
	public static final int FORWARDED_TRUNCATE = 3;
	public static final int FORWARDED_ON = 4;

	public static final int LOG_NONE = 0;
	public static final int LOG_DEBUG = 1;
	public static final int LOG_VERBOSE = 2;

	public static final String PROXY_CHAIN_KEY =
			ProxyConnection.class.getName() + ".PROXY_CHAIN";
	public static final String PROXY_AUTH_KEY =
			ProxyConnection.class.getName() + ".PROXY_AUTH";

	private static final String ERROR_PAGE_FORMAT = "<!DOCTYPE html><html>" +
			"<head><title>%d %s</title></head>" +
			"<body><center><h1>%d %s</h1></center><hr><center>Tuna Proxy/0.1.5</center></body>" +
			"</html>";

	private static Map<Integer, String> reasonMap = new HashMap<>();
	private static Map<Integer, byte[]> errorPageMap = new HashMap<>();

	static {
		try {
			for (Field field : HttpStatus.class.getFields()) {
				String name = field.getName();
				if (!name.startsWith("SC_") || field.getModifiers() !=
						Modifier.PUBLIC + Modifier.STATIC + Modifier.FINAL) {
					continue;
				}
				Integer status = (Integer) field.get(null);
				StringBuilder sb = new StringBuilder();
				for (String s : name.substring(3).split("_")) {
					if (s.equals("HTTP")) {
						sb.append(" HTTP");
					} else if (!s.isEmpty()) {
						sb.append(' ').append(s.charAt(0)).
								append(s.substring(1).toLowerCase());
					}
				}
				String reason = sb.substring(1);
				reasonMap.put(status, reason);
				errorPageMap.put(status, String.format(ERROR_PAGE_FORMAT,
						status, reason, status, reason).getBytes());
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getReason(int status) {
		String reason = reasonMap.get(Integer.valueOf(status));
		return reason == null ? "" + status : reason;
	}

	public static byte[] getDefaultErrorPage(int status) {
		byte[] errorPage = errorPageMap.get(Integer.valueOf(status));
		return errorPage == null ? Bytes.EMPTY_BYTES : errorPage;
	}

	private static String parseHost(String uri, int[] port) throws HttpPacketException {
		if (uri.isEmpty()) {
			throw new HttpPacketException("Invalid Host", uri);
		}
		if (uri.charAt(0) == '[' && uri.charAt(uri.length() - 1) == ']') {
			// "host" is an IPv6 Address
			return uri;
		}
		int colon = uri.lastIndexOf(':');
		if (colon < 0) {
			return uri;
		}
		String host = uri.substring(0, colon);
		String value = uri.substring(colon + 1);
		port[0] = Numbers.parseInt(value, -1);
		if (port[0] < 0 || port[0] > 0xFFFF) {
			throw new HttpPacketException("Invalid Port", value);
		}
		return host;
	}

	LinkedEntry<ProxyConnection> timeoutEntry = null;
	long expire = 0;
	String remote = null;

	private int logLevel;
	private ProxyServer server;
	private ConnectionSession session;
	private ConnectionHandler handler = null;
	private ConnectConnection connect = null;
	private ClientConnection client = null;
	private ByteArrayQueue queue = new ByteArrayQueue();
	private HttpPacket request = new HttpPacket();
	private Map<String, Object> attributeMap = new HashMap<>();

	private void readEx() throws HttpPacketException {
		request.read(queue);
		if (client != null) {
			client.sendRequest(false);
			return;
		}
		if (!request.isCompleteHeader()) {
			return;
		}
		if (timeoutEntry != null) {
			timeoutEntry.remove();
			timeoutEntry = null;
		}

		boolean connectionClose = request.isHttp10() ||
				request.testHeader("CONNECTION", "close") ||
				request.testHeader("PROXY-CONNECTION", "close");
		String username = null, password = null;
		String proxyAuth = request.getHeader("PROXY-AUTHORIZATION");
		if (proxyAuth != null && proxyAuth.toUpperCase().startsWith("BASIC ")) {
			String basic = new String(Base64.getDecoder().decode(proxyAuth.substring(6)));
			int colon = basic.indexOf(':');
			if (colon >= 0) {
				username = basic.substring(0, colon);
				password = basic.substring(colon + 1);
			}
		}
		if (!server.auth.test(username, password)) {
			int status = SC_PROXY_AUTHENTICATION_REQUIRED;
			String realm = server.realm;
			HttpPacket response = new HttpPacket(status,
					getReason(status), server.errorPages.apply(status),
					"Proxy-Authenticate", realm == null || realm.isEmpty() ? "Basic" :
					"Basic realm=\"" + realm + "\"", "Connection", "close");
			response.write(handler, true, false);
			disconnect();
			if (logLevel >= LOG_DEBUG) {
				Log.d("Auth Failed, " + remote);
			}
			return;
		}

		try {
			server.onRequest.accept(this, request);
		} catch (RequestException e) {
			HttpPacket response = e.getResponse();
			if (connectionClose || !request.isComplete()) {
				// Skip reading body
				response.setHeader("Connection", "close");
				response.write(handler, true, false);
				disconnect();
			} else { 
				response.setHeader("Connection", "keep-alive");
				response.write(handler, true, false);
				attributeMap.clear();
				request.reset();
				// No request from peer, so continue reading
				if (queue.length() > 0) {
					readEx();
				}
			}
			return;
		}

		String method = request.getMethod().toUpperCase();
		if (method.equals("CONNECT")) {
			ByteArrayQueue body = request.getBody();
			String proxyChain = (String) attributeMap.get(PROXY_CHAIN_KEY);
			String host;
			int port;
			if (proxyChain == null) {
				String uri = server.lookup.apply(request.getUri().toLowerCase());
				if (uri == null) {
					if (logLevel >= LOG_DEBUG) {
						Log.d("Connection to \"" + request.getUri() +
								"\" is Forbidden, " + remote);
					}
					sendError(SC_FORBIDDEN);
					disconnect();
					return;
				}
				int[] port_ = {443};
				host = parseHost(uri, port_);
				port = port_[0];
			} else {
				int[] port_ = {3128};
				host = parseHost(proxyChain, port_);
				port = port_[0];
				proxyAuth = (String) attributeMap.get(PROXY_AUTH_KEY);
				if (proxyAuth == null) {
					request.removeHeader("PROXY-AUTHORIZATION");
				} else {
					request.setHeader("Proxy-Authorization", proxyAuth);
				}
				request.write(body, true, false);
			}
			connect = new ConnectConnection(server, this,
					proxyChain != null, host, port, logLevel);
			try {
				server.totalPeers ++;
				// onDisconnect() will never be called here in tuna-core-0.1.2
				server.connector.connect(connect, host, port);
			} catch (IOException e) {
				throw new HttpPacketException("Unreachable Host",
						e.getMessage() + ": " + host);
			}
			if (body.length() > 0) {
				connect.handler.send(body.array(), body.offset(), body.length());
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Connection Created, " + connect.toString(false));
			}
			return;
		}

		request.removeHeader("PROXY-AUTHORIZATION");
		request.removeHeader("PROXY-CONNECTION");

		String proxyChain = (String) attributeMap.get(PROXY_CHAIN_KEY);
		String uri = request.getUri();
		boolean secure = false;
		String connectHost;
		String host;
		int port;
		if (proxyChain == null) {
			try {
				// Use "host:port" in URI
				URL url = new URL(uri);
				String proto = url.getProtocol().toLowerCase();
				if (proto.equals("https")) {
					secure = true;
				} else if (!proto.equals("http")) {
					if (logLevel >= LOG_DEBUG) {
						Log.d("\"" + proto + "\" Not Implemented, " + remote);
					}
					sendError(SC_NOT_IMPLEMENTED);
					disconnect();
					return;
				}
				port = url.getPort();
				host = url.getHost() + (port < 0 ? "" : ":" + port);
				String query = url.getQuery();
				uri = url.getPath();
				uri = (uri == null || uri.isEmpty() ? "/" : uri) +
						(query == null || query.isEmpty() ? "" : "?" + query);
			} catch (IOException e) {
				if (!server.enableReverse) {
					throw new HttpPacketException("Invalid URI", uri);
				}
				// Use "Host" in headers if "Reverse" enabled and "host:port" not in URI
				host = request.getHeader("HOST");
				if (host == null) {
					throw new HttpPacketException("Missing Host", "");
				}
			}

			String originalHost = host;
			host = server.lookup.apply(originalHost.toLowerCase());
			if (host == null) {
				if (logLevel >= LOG_DEBUG) {
					Log.d("Request to \"" + originalHost +
							"\" is Forbidden, " + remote);
				}
				sendError(SC_FORBIDDEN);
				disconnect();
				return;
			}
			int[] port_ = {secure ? 443 : 80};
			connectHost = parseHost(host, port_);
			port = port_[0];

			request.setUri(uri);
			if (request.getHeader("HOST") == null) {
				request.setHeader("Host", originalHost);
			}

		} else {
			host = proxyChain;
			int[] port_ = {3128};
			connectHost = parseHost(host, port_);
			port = port_[0];
			proxyAuth = (String) attributeMap.get(PROXY_AUTH_KEY);
			if (proxyAuth != null) {
				request.setHeader("Proxy-Authorization", proxyAuth);
			}
		}

		switch (server.forwardedType) {
		case FORWARDED_DELETE:
			request.removeHeader("X-FORWARDED-FOR");
			break;
		case FORWARDED_OFF:
			request.setHeader("X-Forwarded-For", "unknown");
			break;
		case FORWARDED_TRUNCATE:
		case FORWARDED_ON:
			String remoteAddr = session.getRemoteAddr();
			if (server.forwardedType == FORWARDED_ON) {
				String xff = request.getHeader("X-FORWARDED-FOR");
				if (xff != null && !xff.isEmpty()) {
					remoteAddr = xff + ", " + remoteAddr;
				}
			}
			request.setHeader("X-Forwarded-For", remoteAddr);
			if (!(session instanceof SSLConnectionSession)) {
				request.setHeader("X-Forwarded-Proto", "http");
				break;
			}
			request.setHeader("X-Forwarded-Proto", "https");
			SSLSession ssls = ((SSLConnectionSession) session).getSSLSession();
			request.setHeader("X-Forwarded-SSL-Session-ID", Bytes.toHexLower(ssls.getId()));
			request.setHeader("X-Forwarded-SSL-Cipher", ssls.getCipherSuite());
			Certificate[] certificates;
			try {
				certificates = ssls.getPeerCertificates();
				if (certificates == null) {
					break;
				}
			} catch (IOException e) {
				// Ignored
				break;
			}
			try {
				byte[] pkcs7 = CertificateFactory.getInstance("X509").
						generateCertPath(Arrays.asList(certificates)).getEncoded("PKCS7");
				request.setHeader("X-Forwarded-Certificates",
						Base64.getEncoder().encodeToString(pkcs7));
			} catch (GeneralSecurityException e) {
				Log.w(e.getMessage());
			}
			break;
		}

		client = server.borrowClient(host, secure);
		if (client == null) {
			client = new ClientConnection(server, this, request,
					proxyChain != null, secure, host, logLevel);
			Connection connection;
			if (secure) {
				connection = client.appendFilter(new SSLFilter(server.eventQueue,
						server.executor, server.ssltq, server.sslc,
						SSLFilter.CLIENT, connectHost, port));
			} else {
				connection = client;
			}
			try {
				server.totalPeers ++;
				// onDisconnect() will never be called here in tuna-core-0.1.2
				server.connector.connect(connection, connectHost, port);
			} catch (IOException e) {
				throw new HttpPacketException("Unreachable Host",
						e.getMessage() + ": " + connectHost);
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Created, " + client.toString(false));
			}
		} else {
			client.setProxy(this, request, proxyChain != null);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Reused, " + client.toString(false));
			}
		}
		client.head = method.equals("HEAD");
		client.begin(connectionClose);
	}

	void read() {
		if (queue.length() == 0) {
			return;
		}
		try {
			readEx();
		} catch (HttpPacketException e) {
			if (logLevel >= LOG_DEBUG) {
				Log.d(e.getMessage() + ", " + remote);
			}
			if (client == null || !client.begun) {
				String type = e.getType();
				sendError(type == HttpPacketException.HEADER_SIZE ?
						SC_REQUEST_ENTITY_TOO_LARGE :
						type == HttpPacketException.VERSION ?
						SC_HTTP_VERSION_NOT_SUPPORTED : SC_BAD_REQUEST);
			}
			disconnect();
		}
	}

	void sendError(int status) {
		byte[] body = server.errorPages.apply(status);
		new HttpPacket(status, getReason(status), body,
				"Connection", "close").write(handler, true, false);
	}

	void disconnectWithoutConnect() {
		connect = null;
		disconnect();
	}

	void disconnectWithoutClient() {
		client = null;
		disconnect();
	}

	/**
	 * Keep <b>Proxy</b> Alive, then return or close <b>Client</b><p>
	 * Must Follow a "<code>server.onComplete.accept(proxy)</code>"
	 *
	 * @param closed <code>false</code> to return Client to pool and <code>true</code> to close Client
	 */
	void reset(boolean closed) {
		attributeMap.clear();
		request.reset();
		handler.setBufferSize(MAX_BUFFER_SIZE);
		server.proxyTimeoutQueue.offer(this);
		// "proxy" may close or return "client" before reset
		if (logLevel >= LOG_VERBOSE && client != null) {
			Log.v((closed ? "Client Closed" : "Client Kept Alive") +
					" and Request Unblocked due to Complete Request and Response, " +
					client.toString(false));
		}
		if (!closed && client != null) {
			server.returnClient(client);
		}
		client = null;
		read();
	}

	public ProxyConnection(ProxyServer server) {
		this.server = server;
		logLevel = server.logLevel;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (connect != null) {
			connect.handler.send(b, off, len);
			return;
		}

		queue.add(b, off, len);
		if (request.isComplete()) {
			handler.setBufferSize(0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Request Blocked due to Complete Request but Incomplete Response, " +
						(client == null ? remote : client.toString(false)));
			}
		} else {
			read();
		}
	}

	@Override
	public void onQueue(int size) {
		if (connect != null) {
			connect.handler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v((size == 0 ? "Connection Unblocked, " :
						"Connection Blocked (" + size + "), ") + connect.toString(true));
			}
		} else if (client != null) {
			client.handler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v((size == 0 ? "Response Unblocked, " :
						"Response Blocked (" + size + "), ") + client.toString(true));
			}
		}
	}

	@Override
	public void onConnect(ConnectionSession session_) {
		session = session_;
		remote = session.getRemoteAddr() + ":" + session.getRemotePort();
		server.getConnections().add(this);
		server.proxyTimeoutQueue.offer(this);
	}

	@Override
	public void onDisconnect() {
		if (connect != null) {
			connect.disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Connection Closed, " + connect.toString(false));
			}
			connect = null;
		} else if (client != null) {
			client.disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Proxy Connection Closed, " + client.toString(false));
			}
			client = null;
		}
		if (server.getConnections().remove(this) && timeoutEntry != null) {
			timeoutEntry.remove();
			timeoutEntry = null;
		}
	}

	@Override
	public long getExpire() {
		return expire;
	}

	@Override
	public void setExpire(long expire) {
		this.expire = expire;
	}

	@Override
	public void setTimeoutEntry(LinkedEntry<ProxyConnection> timeoutEntry) {
		this.timeoutEntry = timeoutEntry;
	}

	public Object getAttribute(String name) {
		return attributeMap.get(name);
	}

	public Set<String> getAttributeNames() {
		return attributeMap.keySet();
	}

	public void setAttribute(String name, Object value) {
		attributeMap.put(name, value);
	}

	public void removeAttribute(String key) {
		attributeMap.remove(key);
	}

	public ConnectionHandler getHandler() {
		return handler;
	}

	public ConnectionSession getSession() {
		return session;
	}

	public void disconnect() {
		handler.disconnect();
		onDisconnect();
	}
}