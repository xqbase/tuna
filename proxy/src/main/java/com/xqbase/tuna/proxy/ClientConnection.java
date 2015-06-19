package com.xqbase.tuna.proxy;

import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.http.HttpPacketException;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.tuna.proxy.util.LinkedEntry;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.util.Log;

/** Connection for request except <b>CONNECT</b> */
class ClientConnection extends PeerConnection implements HttpStatus {
	LinkedEntry<ClientConnection> linkedEntry = null, timeoutEntry = null;
	long expire;
	String host;
	boolean secure, begun = false;

	private HttpPacket request, response = new HttpPacket();
	private boolean chunked = false, requestClose = false, responseClose = false;

	ClientConnection(ProxyServer server, ProxyConnection proxy, HttpPacket request,
			boolean proxyChain, boolean secure, String host, int logLevel) {
		super(server, proxy, logLevel);
		this.server = server;
		this.request = request;
		this.host = host;
		this.secure = secure;
		setRemote(proxyChain);
	}

	void clear() {
		proxy = null;
		proxyHandler = null;
		request = null;
		response.reset();
		remote = (secure ? "https://" : "http://") + host;
	}

	void setProxy(ProxyConnection proxy, HttpPacket request, boolean proxyChain) {
		this.proxy = proxy;
		this.request = request;
		proxyHandler = proxy.getHandler();
		setRemote(proxyChain);
	}

	void begin(boolean head, boolean connectionClose) {
		begun = chunked = false;
		response.setType(head ? HttpPacket.TYPE_RESPONSE_HEAD : request.isHttp10() ?
				HttpPacket.TYPE_RESPONSE_HTTP10 : HttpPacket.TYPE_RESPONSE);
		requestClose = connectionClose;
		sendRequest(true);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (proxy == null) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unexpected Response in Keep-Alive: \"" +
						new String(b, off, len) + "\", " + toString(true));
			}
			disconnect();
			server.removeClient(this);
			return;
		}
		if (response.isComplete()) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unexpected Response: \"" + new String(b, off, len) +
						"\", " + toString(true));
			}
			disconnect();
			proxy.client = null;
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
				server.onComplete.accept(proxy);
				disconnect();
				if (response.isCompleteHeader()) {
					proxy.client = null;
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
			server.onResponse.accept(proxy, response);
			int status_ = response.getStatus();
			if (status_ != 100) { // Not Necessary to Support "102 Processing"
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
		if (proxy != null) {
			super.onQueue(size);
		}
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		if (proxy == null) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Keep-Alive, " + toString(true));
			}
			server.removeClient(this);
			return;
		}
		server.onComplete.accept(proxy);
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
				Log.d((connected ? "Incomplete Header, " :
						"Client Connection Failed, ") + toString(true));
			} else if (logLevel >= LOG_VERBOSE) {
				Log.v("Client Lost in Response, " + toString(true));
			}
		}
		if (connected) {
			// Just disconnect because request is not saved.
			// Most browsers will retry request.
			proxy.client = null;
			proxy.disconnect();
		} else {
			proxy.sendError(SC_GATEWAY_TIMEOUT);
			reset(true);
		}
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
			// Both "requestClose" and "responseClose" must be "false" 
			server.onComplete.accept(proxy);
			reset(false);
		}
	}

	private void sendResponse(boolean begin) {
		response.write(proxyHandler, begin, chunked);
		if (!response.isComplete()) {
			return;
		}
		if (requestClose) {
			server.onComplete.accept(proxy);
			// Must Disconnect "proxy" Before "server.returnClient"
			proxy.client = null;
			proxy.disconnect();
			if (responseClose) {
				disconnect();
			} else {
				handler.setBufferSize(MAX_BUFFER_SIZE);
				if (logLevel >= LOG_VERBOSE) {
					Log.v("Response Sent and Unblocked, " + toString(true));
				}
				server.returnClient(this);
			}
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Proxy Connection Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Request, " + toString(false));
			}
			return;
		}
		if (responseClose) {
			server.onComplete.accept(proxy);
			disconnect();
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Client Closed due to HTTP/1.0 or " +
						"\"Connection: close\" in Response, " + toString(true));
			}
			if (request.isComplete()) {
				// Both Request and Response Completed: Keep Alive Proxy
				reset(true);
			} else {
				// Request Not Completed: Just Disconnect Proxy
				proxy.client = null;
				proxy.disconnect();
			}
		} else {
			handler.setBufferSize(MAX_BUFFER_SIZE);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Response Sent and Unblocked, " + toString(true));
			}
			if (request.isComplete()) {
				// Both Request and Response Completed: Keep Alive Proxy
				server.onComplete.accept(proxy);
				reset(false);
			}
			// If Request Not Completed: Just Wait for "sendRequest"
		}
	}

	private void setRemote(boolean proxyChain) {
		String uri = request.getUri();
		remote = proxyChain ? uri + " via " + host :
				(secure ? "https://" : "http://") + host + (uri == null ? "" : uri);
	}

	/**
	 * Keep <b>Proxy</b> Alive, then return or close <b>Client</b><p>
	 * Must Follow a "<code>server.onComplete.accept(proxy)</code>"
	 *
	 * @param closed <code>false</code> to return Client to pool and <code>true</code> to close Client
	 */
	private void reset(boolean closed) {
		proxy.getBindings().clear();
		proxy.client = null;
		proxyHandler.setBufferSize(MAX_BUFFER_SIZE);
		request.reset();
		if (logLevel >= LOG_VERBOSE) {
			Log.v((closed ? "Client Closed" : "Client Kept Alive") +
					" and Request Unblocked due to Complete Request and Response, " +
					toString(false));
		}
		ProxyConnection proxy_ = proxy;
		if (!closed) {
			// "server.returnClient" will set "proxy" to "null"
			server.returnClient(this);
		}
		proxy_.read();
	}
}