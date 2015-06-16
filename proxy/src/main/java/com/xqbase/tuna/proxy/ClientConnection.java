package com.xqbase.tuna.proxy;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.http.HttpPacketException;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.tuna.proxy.util.LinkedEntry;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.util.Log;

/** Connection for request except <b>CONNECT</b> */
class ClientConnection extends LinkedEntry implements Connection, HttpStatus {
	private static final int LOG_DEBUG = ProxyConnection.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyConnection.LOG_VERBOSE;

	private TimeoutEntry timeoutEntry = null;
	private ProxyServer server;
	private ProxyConnection proxy;
	private ConnectionHandler proxyHandler, handler;
	private HttpPacket request, response = new HttpPacket();
	private boolean proxyChain, secure, established = false, begun = false, chunked = false,
			requestClose = false, responseClose = false;
	private int logLevel;
	private String host, local = " (0.0.0.0:0)";

	ClientConnection(ProxyServer server, ProxyConnection proxy, HttpPacket request,
			boolean proxyChain, boolean secure, String host, int logLevel) {
		this.server = server;
		this.proxy = proxy;
		this.request = request;
		this.proxyChain = proxyChain;
		this.secure = secure;
		this.host = host;
		this.logLevel = logLevel;
		proxyHandler = proxy == null ? null : proxy.getHandler();
	}

	void setProxy(ProxyConnection proxy, HttpPacket request, boolean proxyChain) {
		this.proxy = proxy;
		this.request = request;
		this.proxyChain = proxyChain;
		proxyHandler = proxy.getHandler();
	}

	String toString(boolean resp) {
		String uri = request.getUri();
		return proxy.getRemote() + (resp ? " <= " : " => ") +
				(proxyChain ? uri + " via " + host :
				(secure ? "https://" : "http://") + host +
				(uri == null ? "" : uri)) + local;
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

	TimeoutEntry getTimeoutEntry() {
		return timeoutEntry;
	}

	void setTimeoutEntry(TimeoutEntry timeoutEntry) {
		this.timeoutEntry = timeoutEntry;
	}

	boolean isSecure() {
		return secure;
	}

	String getHost() {
		return host;
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
		if (!proxy.isCurrentClient(this)) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unexpected Response in Keep-Alive: \"" +
						new String(b, off, len) + "\", " + toString(true));
			}
			handler.disconnect();
			server.removeClient(this);
			return;
		}
		if (response.isComplete()) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unexpected Response: \"" + new String(b, off, len) +
						"\", " + toString(true));
			}
			handler.disconnect();
			proxy.disconnect();
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
				handler.disconnect();
				if (response.isCompleteHeader()) {
					proxy.disconnect();
				} else {
					proxy.sendError(SC_BAD_GATEWAY);
					reset(true);
				}
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
			server.onResponse(proxy, response);
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
		if (!proxy.isCurrentClient(this)) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Keep-Alive, " + toString(true));
			}
			server.removeClient(this);
			return;
		}
		if (chunked) {
			response.endRead();
			response.write(proxyHandler, false, chunked);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost and Proxy Responded a Final Chunk, " +
						toString(true));
			}
			reset(true);
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
		onComplete();
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
			reset(false);
		}
	}

	private void onComplete() {
		server.onComplete(proxy);
		proxy.getBindings().clear();
	}

	private void sendResponse(boolean begin) {
		response.write(proxyHandler, begin, chunked);
		if (!response.isComplete()) {
			return;
		}
		if (requestClose) {
			onComplete();
			proxy.disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Proxy Connection Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Request, " + toString(false));
			}
			return;
		}
		if (responseClose) {
			handler.disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Client Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Response, " + toString(true));
			}
		} else {
			handler.setBufferSize(MAX_BUFFER_SIZE);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Unblocked, " + toString(true));
			}
		}
		if (request.isComplete()) {
			reset(responseClose);
		}
	}

	private void reset(boolean closed) {
		onComplete();
		proxyHandler.setBufferSize(MAX_BUFFER_SIZE);
		request.reset();
		if (logLevel >= LOG_VERBOSE) {
			Log.v((closed ? "Client Closed" : "Client Kept Alive") +
					" and Request Unblocked due to Complete Request and Response, " +
					toString(false));
		}
		if (!closed) {
			response.reset();
			server.returnClient(this);
		}
		proxy.clearCurrentClient();
		proxy.read();
	}

	@Override
	public ClientConnection getNext() {
		return (ClientConnection) super.getNext();
	}

	@Override
	public ClientConnection getPrev() {
		return (ClientConnection) super.getPrev();
	}
}