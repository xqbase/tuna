package com.xqbase.tuna.proxy;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.util.Log;

abstract class PeerConnection implements Connection {
	static final int
			LOG_DEBUG	= ProxyConnection.LOG_DEBUG,
			LOG_VERBOSE	= ProxyConnection.LOG_VERBOSE;

	ProxyServer server;
	ProxyConnection proxy;
	ConnectionHandler proxyHandler, handler;
	boolean connected, disconnected = false;
	int logLevel;
	String remote, local = " / 0.0.0.0:0";

	PeerConnection(ProxyServer server, ProxyConnection proxy, int logLevel) {
		this.server = server;
		this.proxy = proxy;
		this.logLevel = logLevel;
		proxyHandler = proxy.getHandler();
	}

	String toString(boolean resp) {
		return (proxy == null ? "0.0.0.0:0" : proxy.remote) +
				local + (resp ? " <= " : " => ") + remote;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onQueue(int size) {
		proxyHandler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
		if (logLevel >= LOG_VERBOSE) {
			Log.v((size == 0 ? "Request Unblocked, " :
					"Request Blocked (" + size + "), ") + toString(false));
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		connected = true;
		local = " / " + session.getLocalAddr() + ":" + session.getLocalPort();
		if (logLevel >= LOG_VERBOSE) {
			Log.v("Connection Established, " + toString(false));
		}
	}

	@Override
	public void onDisconnect() {
		decPeers();
	}

	void disconnect() {
		handler.disconnect();
		decPeers();
	}

	private void decPeers() {
		if (!disconnected) {
			disconnected = true;
			server.decPeers();
		}
	}
}