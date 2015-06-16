package com.xqbase.tuna.proxy;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.util.Log;

/** Connection for <b>CONNECT</b> */
class PeerConnection implements Connection, HttpStatus {
	private static final int LOG_DEBUG = ProxyConnection.LOG_DEBUG;
	private static final int LOG_VERBOSE = ProxyConnection.LOG_VERBOSE;

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