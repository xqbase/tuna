package com.xqbase.tuna.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

class PeerConnection<T extends PeerConnection<?>> implements Connection {
	T peer;
	ConnectionHandler handler;

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onQueue(int size) {
		if (peer != null) {
			peer.handler.setBufferSize(size == 0 ? MAX_BUFFER_SIZE : 0);
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			peer.peer = null;
			peer.handler.disconnect();
		}
	}
}

class RemoteConnection extends PeerConnection<TunnelConnection> {
	StringBuilder response = new StringBuilder();

	RemoteConnection(TunnelConnection peer) {
		this.peer = peer;
	}

	private void onConnect() {
		ByteArrayQueue queue = peer.queue;
		if (queue.length() > 0) {
			handler.send(queue.array(), queue.offset(), queue.length());
		}
		peer.queue = null;
		response = null;
	}

	@Override
	public void onConnect(ConnectionSession session) {
		if (response == null || peer == null) {
			return;
		}
		if (peer.request == null) {
			onConnect();
		} else {
			handler.send(peer.request);
		}
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer == null) {
			return;
		}
		if (response == null) {
			peer.handler.send(b, off, len);
			return;
		}
		response.append(new String(b, off, len, StandardCharsets.ISO_8859_1));
		int index = response.indexOf("\r\n\r\n");
		if (index < 0) {
			return;
		}
		if (peer.sslFilter != null) {
			peer.request = null;
			peer.sslFilter.setEnabled(true);
			return;
		}
		if (index + 4 < response.length()) {
			peer.handler.send(response.substring(index + 4).
					getBytes(StandardCharsets.ISO_8859_1));
		}
		onConnect();
	}
}

class TunnelConnection extends PeerConnection<RemoteConnection> {
	private ConnectorImpl connector;
	private String proxyHost;
	private int proxyPort;

	byte[] request;
	SSLFilter sslFilter;
	ByteArrayQueue queue = new ByteArrayQueue();

	TunnelConnection(ConnectorImpl connector, String proxyHost, int proxyPort,
			byte[] request, TimeoutQueue<SSLFilter> ssltq, SSLContext sslc) {
		this.connector = connector;
		this.proxyHost = proxyHost;
		this.proxyPort = proxyPort;
		this.request = request;
		if (ssltq == null || sslc == null) {
			sslFilter = null;
		} else {
			sslFilter = new SSLFilter(connector, connector,
					ssltq, sslc, SSLFilter.CLIENT);
			sslFilter.setEnabled(false);
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		peer = new RemoteConnection(this);
		try {
			connector.connect(sslFilter == null ? peer :
				peer.appendFilter(sslFilter), proxyHost, proxyPort);
		} catch (IOException e) {
			peer = null;
			handler.disconnect();
		}
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer == null) {
			return;
		}
		if (queue == null) {
			peer.handler.send(b, off, len);
		} else {
			queue.add(b, off, len);
		}
	}
}

public class ProxyTunnel {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 5) {
			System.out.println("ProxyTunnel Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.ProxyTunnel <local-port> <remote-host> " +
					"<remote-port> [-s] <proxy-host> <porxy-port> [<auth>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("ProxyTunnel.", 16777216, 10));

		int localPort = Numbers.parseInt(args[0], 1, 65535);
		String remoteHost = args[1];
		int remotePort = Numbers.parseInt(args[2], 1, 65535);
		boolean ssl = "-s".equalsIgnoreCase(args[3]);
		int param3 = ssl ? 4 : 3;
		String proxyHost = args[param3];
		int proxyPort = Numbers.parseInt(args[param3 + 1], 1, 65535);
		StringBuilder sb = new StringBuilder("CONNECT " +
				remoteHost + ":" + remotePort + " HTTP/1.1\r\n");
		if (args.length > param3 + 2) {
			sb.append("Proxy-Authorization: Basic " + Base64.getEncoder().
					encodeToString(args[param3 + 2].getBytes()) + "\r\n");
		}
		sb.append("\r\n");
		byte[] request = sb.toString().getBytes();
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);
			TimeoutQueue<SSLFilter> ssltq;
			SSLContext sslc;
			if (ssl) {
				ssltq = SSLFilter.getTimeoutQueue(10000);
				connector.scheduleDelayed(ssltq, 1000, 1000);
				sslc = SSLContexts.get(null, 0);
			} else {
				ssltq = null;
				sslc = null;
			}
			connector.add(() -> new TunnelConnection(connector,
					proxyHost, proxyPort, request, ssltq, sslc), localPort);
			Log.i(String.format("ProxyTunnel Started (%s->%s:%s%s) via %s:%s",
					"" + localPort, remoteHost, "" + remotePort, ssl ? "s" : "",
					proxyHost, "" + proxyPort));
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("ProxyTunnel Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}