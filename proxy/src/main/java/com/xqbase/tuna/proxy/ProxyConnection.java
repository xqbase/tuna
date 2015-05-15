package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;

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
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;

/** Connection for <b>CONNECT</b> */
class PeerConnection implements Connection, HttpStatus {
	private static final int LOG_DEBUG = ProxyContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyContext.LOG_VERBOSE;

	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	private ProxyConnection peer;
	private ConnectionHandler peerHandler, handler;
	private boolean proxyChain, established = false;
	// For DEBUG only
	private int logLevel, port;
	private String host, local = " (0.0.0.0:0)";

	PeerConnection(ProxyConnection peer, ConnectionHandler peerHandler,
			boolean proxyChain, int logLevel, String host, int port) {
		this.peer = peer;
		this.peerHandler = peerHandler;
		this.proxyChain = proxyChain;
		this.logLevel = logLevel;
		this.host = host;
		this.port = port;
	}

	String toString(boolean resp) {
		return peer.getRemote() + (resp ? " <= " : " => ") + host + ":" + port + local;
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
	public void onQueue(int size) {
		peerHandler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((size == 0 ? "Connection Unblocked, " :
					"Connection Blocked (" + size + "), ") + toString(false));
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		if (!proxyChain) {
			peerHandler.send(CONNECTION_ESTABLISHED);
		}
		established = true;
		local = " (" + session.getLocalAddr() + ":" + session.getLocalPort() + ")";
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
			peer.sendError(SC_GATEWAY_TIMEOUT);
		} else if (logLevel >= LOG_VERBOSE) {
			Log.v("Connection Lost, " + toString(true));
		}
		peer.disconnect();
	}
}

/** Connection for request except <b>CONNECT</b> */
class ClientConnection implements Connection, HttpStatus {
	private static final int LOG_DEBUG = ProxyContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyContext.LOG_VERBOSE;

	private ProxyConnection proxy;
	private ConnectionHandler proxyHandler, handler;
	private HttpPacket request, response = new HttpPacket();
	private boolean secure, established = false, begun = false, chunked = false,
			requestClose = false, responseClose = false;
	private int logLevel;
	private String host, local = " (0.0.0.0:0)";

	ClientConnection(ProxyConnection proxy, ConnectionHandler proxyHandler,
			int logLevel, HttpPacket request, boolean secure, String host) {
		this.proxy = proxy;
		this.proxyHandler = proxyHandler;
		this.logLevel = logLevel;
		this.request = request;
		this.secure = secure;
		this.host = host;
	}

	String toString(boolean resp) {
		String uri = request.getUri();
		return proxy.getRemote() + (resp ? " <= " : " => ") +
				(secure ? "https://" : "http://") + host + (uri == null ? "" : uri) + local;
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
		while (true) {
			try {
				response.read(queue);
			} catch (HttpPacketException e) {
				if (logLevel >= LOG_DEBUG) {
					Log.d(e.getMessage() + ", " + toString(true));
				}
				// Disconnect for a Bad Response
				if (!response.isCompleteHeader()) {
					proxy.sendError(SC_BAD_GATEWAY);
				}
				proxy.onComplete();
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
			proxy.onResponse(response);
			int status = response.getStatus();
			if (status != 100) { // Not Necessary to Support "102 Processing"
				if (logLevel >= LOG_VERBOSE) {
					Log.v("Response Header Received, " + toString(true));
				}
				break;
			}
			response.write(proxyHandler, true, false);
			response.reset();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("\"100 Continue\" Received, " + toString(true));
			}
		}
		begun = true;
		responseClose = requestClose || response.isHttp10() ||
				response.testHeader("CONNECTION", "close") ||
				queue.length() > 0;
		// Write in Chunked mode when Request is HTTP/1.1 and
		// Response is HTTP/1.0 and has no Content-Length
		if (!request.isHttp10() && response.isHttp10() &&
				response.getHeader("CONTENT-LENGTH") == null) {
			chunked = true;
			response.setHeader("Transfer-Encoding", "chunked");
		}
		response.setHttp10(request.isHttp10());
		if (response.getStatus() == 101) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("\"101 Switching Protocols\" Received, " + toString(true));
			}
			request.continueRead();
			proxy.read();
		} else {
			response.setHeader("Connection", requestClose ? "close" : "keep-alive");
		}
		sendResponse(true);
	}

	@Override
	public void onQueue(int size) {
		if (!proxy.isCurrentClient(this)) {
			return;
		}
		proxyHandler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((size == 0 ? "Request Unblocked, " :
					"Request Blocked (" + size + "), ") + toString(false));
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		established = true;
		local = " (" + session.getLocalAddr() + ":" + session.getLocalPort() + ")";
		if (logLevel >= LOG_VERBOSE) {
			Log.v("Client Connection Established, " + toString(false));
		}
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
						"Client Connection Failed, ") + toString(true));
			} else if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Response, " + toString(true));
			}
		}
		if (!established) {
			proxy.sendError(SC_GATEWAY_TIMEOUT);
		}
		// Just disconnect because request is not saved.
		// Most browsers will retry request. 
		proxy.onComplete();
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
			proxy.onComplete();
			proxy.disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Proxy Connection Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Request, " + toString(false));
			}
			return;
		}
		if (responseClose) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Client Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Response, " + toString(true));
			}
			handler.disconnect();
			remove();
		} else {
			handler.setBufferSize(MAX_BUFFER_SIZE);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Unblocked, " + toString(true));
			}
		}
		if (request.isComplete()) {
			reset();
		}
	}

	private void reset() {
		proxy.onComplete();
		request.reset();
		response.reset();
		proxyHandler.setBufferSize(MAX_BUFFER_SIZE);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((proxy.getClientMap(secure).get(host) == null ? "Client Closed" :
					"Client Kept Alive") + " and Request Unblocked due to " +
					"Complete Request and Response, " + toString(false));
		}
		proxy.clearCurrentClient();
		proxy.read();
	}
}

public class ProxyConnection implements Connection, HttpStatus {
	public static final String SESSION_KEY = ConnectionSession.class.getName();
	public static final String PROXY_CHAIN_KEY =
			ProxyConnection.class.getName() + ".PROXY_CHAIN";
	public static final String PROXY_AUTH_KEY =
			ProxyConnection.class.getName() + ".PROXY_AUTH";

	private static final int LOG_DEBUG = ProxyContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyContext.LOG_VERBOSE;

	private int logLevel; 
	private ProxyContext context;
	private ConnectionHandler handler;
	private ConnectionSession session;
	private String remote = null;
	private ByteArrayQueue queue = new ByteArrayQueue();
	private HttpPacket request = new HttpPacket();
	private HashMap<String, Object> bindings = new HashMap<>();
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
		return remote;
	}

	void sendError(int status) {
		byte[] body = context.getErrorPage(status);
		new HttpPacket(status, ProxyContext.getReason(status),
				body, "Connection", "close").write(handler, true, false);
	}

	void onResponse(HttpPacket response) {
		context.onResponse(bindings, response);
	}

	void onComplete() {
		context.onComplete(bindings);
		bindings.clear();
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
		if (!context.auth(username, password)) {
			String realm = context.getRealm();
			int status = SC_PROXY_AUTHENTICATION_REQUIRED;
			HttpPacket response = new HttpPacket(status,
					ProxyContext.getReason(status), context.getErrorPage(status),
					"Proxy-Authenticate", realm == null || realm.isEmpty() ?
					"Basic" : "Basic realm=\"" + realm + "\"");
			if (connectionClose || !request.isComplete()) {
				// Skip reading body
				response.setHeader("Connection", "close");
				response.write(handler, true, false);
				disconnect();
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

		bindings.put(SESSION_KEY, session);
		try {
			context.onRequest(bindings, request);
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
				bindings.clear();
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
			String host = (String) bindings.get(PROXY_CHAIN_KEY);
			int port;
			boolean proxyChain;
			if (host == null) {
				String uri = context.lookup(request.getUri().toLowerCase());
				if (uri == null) {
					if (logLevel >= LOG_DEBUG) {
						Log.d("Connection to \"" + request.getUri() +
								"\" is Forbidden, " + getRemote());
					}
					sendError(SC_FORBIDDEN);
					disconnect();
					return;
				}
				int colon = uri.lastIndexOf(':');
				if (colon < 0) {
					throw new HttpPacketException("Invalid Destination", uri);
				}
				host = uri.substring(0, colon);
				String value = uri.substring(colon + 1);
				port = Numbers.parseInt(value, -1);
				if (port < 0 || port > 0xFFFF) {
					throw new HttpPacketException("Invalid Port", value);
				}
				proxyChain = false;
			} else {
				port = 3128;
				int colon = host.lastIndexOf(':');
				if (colon < 0) {
					port = 3128;
				} else {
					port = Numbers.parseInt(host.substring(colon + 1), 3128, 0, 0xFFFF);
					host = host.substring(0, colon);
				}
				proxyAuth = (String) bindings.get(PROXY_AUTH_KEY);
				if (proxyAuth == null) {
					request.removeHeader("PROXY-AUTHORIZATION");
				} else {
					request.setHeader("Proxy-Authorization", proxyAuth);
				}
				request.write(body, true, false);
				proxyChain = true;
			}
			peer = new PeerConnection(this, handler, proxyChain, logLevel, host, port);
			try {
				context.connect(peer, host, port);
			} catch (IOException e) {
				throw new HttpPacketException("Invalid Host", host);
			}
			if (body.length() > 0) {
				peer.getHandler().send(body.array(), body.offset(), body.length());
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Connection Created, " + peer.toString(false));
			}
			return;
		}

		request.removeHeader("PROXY-AUTHORIZATION");
		request.removeHeader("PROXY-CONNECTION");

		String host = (String) bindings.get(PROXY_CHAIN_KEY);
		String uri = request.getUri();
		boolean secure = false;
		String connectHost;
		int port;
		if (host == null) {
			try {
				// Use "host:port" in URI
				URL url = new URL(uri);
				String proto = url.getProtocol().toLowerCase();
				if (proto.equals("https")) {
					secure = true;
				} else if (!proto.equals("http")) {
					if (logLevel >= LOG_DEBUG) {
						Log.d("\"" + proto + "\" Not Implemented, " + getRemote());
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
				if (!context.isEnableReverse()) {
					throw new HttpPacketException("Invalid URI", uri);
				}
				// Use "Host" in headers if "Reverse" enabled and "host:port" not in URI
				host = request.getHeader("HOST");
				if (host == null) {
					throw new HttpPacketException("Missing Host", "");
				}
			}

			String originalHost = host;
			host = context.lookup(originalHost.toLowerCase());
			if (host == null) {
				if (logLevel >= LOG_DEBUG) {
					Log.d("Request to \"" + originalHost +
							"\" is Forbidden, " + getRemote());
				}
				sendError(SC_FORBIDDEN);
				disconnect();
				return;
			}
			int colon = host.lastIndexOf(':');
			if (colon < 0) {
				connectHost = host;
				port = secure ? 443 : 80;
			} else {
				connectHost = host.substring(0, colon);
				String value = host.substring(colon + 1);
				port = Numbers.parseInt(value, -1);
				if (port < 0 || port > 0xFFFF) {
					throw new HttpPacketException("Invalid Port", value);
				}
			}

			request.setUri(uri);
			if (request.getHeader("HOST") == null) {
				request.setHeader("Host", originalHost);
			}

		} else {
			int colon = host.lastIndexOf(':');
			if (colon < 0) {
				connectHost = host;
				port = 3128;
			} else {
				connectHost = host.substring(0, colon);
				port = Numbers.parseInt(host.substring(colon + 1), 3128, 0, 0xFFFF);
			}
			proxyAuth = (String) bindings.get(PROXY_AUTH_KEY);
			if (proxyAuth != null) {
				request.setHeader("Proxy-Authorization", proxyAuth);
			}
		}

		int forwardedType = context.getForwardedType();
		switch (forwardedType) {
		case ProxyContext.FORWARDED_DELETE:
			request.removeHeader("X-FORWARDED-FOR");
			break;
		case ProxyContext.FORWARDED_OFF:
			request.setHeader("X-Forwarded-For", "unknown");
			break;
		case ProxyContext.FORWARDED_TRUNCATE:
		case ProxyContext.FORWARDED_ON:
			String remoteAddr = session.getRemoteAddr();
			if (forwardedType == ProxyContext.FORWARDED_ON) {
				String xff = request.getHeader("X-FORWARDED-FOR");
				if (xff != null && !xff.isEmpty()) {
					remoteAddr = xff + ", " + remoteAddr;
				}
			}
			request.setHeader("X-Forwarded-For", remoteAddr);
			if (!(session instanceof SSLConnectionSession)) {
				break;
			}
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

		client = getClientMap(secure).get(host);
		if (client == null) {
			client = new ClientConnection(this, handler, logLevel, request, secure, host);
			getClientMap(secure).put(host, client);
			Connection connection;
			if (secure) {
				connection = client.appendFilter(new SSLFilter(context, context,
						context.getSSLContext(), SSLFilter.CLIENT, connectHost, port));
			} else {
				connection = client;
			}
			try {
				context.connect(connection, connectHost, port);
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
				String type = e.getType();
				sendError(type == HttpPacketException.HEADER_SIZE ?
						SC_REQUEST_ENTITY_TOO_LARGE :
						type == HttpPacketException.VERSION ?
						SC_HTTP_VERSION_NOT_SUPPORTED : SC_BAD_REQUEST);
			}
			disconnect();
		}
	}

	public ProxyConnection(ProxyContext context) {
		this.context = context;
		logLevel = context.getLogLevel();
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
	public void onQueue(int size) {
		if (peer != null) {
			peer.getHandler().setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
			if (logLevel >= LOG_VERBOSE) {
				Log.v((size == 0 ? "Connection Unblocked, " :
						"Connection Blocked (" + size + "), ") + peer.toString(true));
			}
		} else if (client != null) {
			client.getHandler().setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
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

	public void disconnect() {
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
}