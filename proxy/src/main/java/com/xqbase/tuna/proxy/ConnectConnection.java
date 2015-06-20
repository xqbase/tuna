package com.xqbase.tuna.proxy;

import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.util.Log;

/** Connection for <b>CONNECT</b> */
class ConnectConnection extends PeerConnection implements HttpStatus {
	private static final byte[] CONNECTION_ESTABLISHED =
			"HTTP/1.0 200 Connection Established\r\n\r\n".getBytes();

	private boolean proxyChain;

	ConnectConnection(ProxyServer server, ProxyConnection proxy,
			boolean proxyChain, String host, int port, int logLevel) {
		super(server, proxy, logLevel);
		this.proxyChain = proxyChain;
		remote = host + ":" + port;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		proxyHandler.send(b, off, len);
	}

	@Override
	public void onConnect(ConnectionSession session) {
		super.onConnect(session);
		if (!proxyChain) {
			proxyHandler.send(CONNECTION_ESTABLISHED);
		}
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		if (!connected) {
			if (logLevel >= LOG_DEBUG) {
				Log.d("Connection Failed, " + toString(false));
			}
			proxy.sendError(SC_GATEWAY_TIMEOUT);
		} else if (logLevel >= LOG_VERBOSE) {
			Log.v("Connection Lost, " + toString(true));
		}
		proxy.disconnectWithoutConnect();
	}
}