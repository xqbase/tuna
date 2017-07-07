package com.xqbase.tuna.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Logger;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

class RemoteConnection implements Connection {
	TunnelConnection peer;
	ConnectionHandler handler;
	StringBuilder response = new StringBuilder();

	RemoteConnection(TunnelConnection peer) {
		this.peer = peer;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onConnect(ConnectionSession session) {
		if (response != null && peer != null) {
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
		if (index + 4 < response.length()) {
			peer.handler.send(response.substring(index + 4).
					getBytes(StandardCharsets.ISO_8859_1));
		}
		ByteArrayQueue queue = peer.queue;
		if (queue.length() > 0) {
			handler.send(queue.array(), queue.offset(), queue.length());
		}
		peer.queue = null;
		response = null;
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

class TunnelConnection implements Connection {
	private Connector connector;
	private String proxyHost;
	private int proxyPort;

	RemoteConnection peer;
	ConnectionHandler handler;
	byte[] request;
	ByteArrayQueue queue = new ByteArrayQueue();

	TunnelConnection(Connector connector,
			String proxyHost, int proxyPort, byte[] request) {
		this.connector = connector;
		this.proxyHost = proxyHost;
		this.proxyPort = proxyPort;
		this.request = request;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onConnect(ConnectionSession session) {
		peer = new RemoteConnection(this);
		try {
			connector.connect(peer, proxyHost, proxyPort);
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

public class ProxyTunnel {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("ProxyTunnel Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.ProxyTunnel <local-port> <remote-host> " +
					"<remote-port> <proxy-host> <porxy-port> [<auth>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("ProxyTunnel.", 16777216, 10));

		int localPort = Numbers.parseInt(args[0], 1, 65535);
		String remoteHost = args[1];
		int remotePort = Numbers.parseInt(args[2], 1, 65535);
		String proxyHost = args[3];
		int proxyPort = Numbers.parseInt(args[4], 1, 65535);
		StringBuilder sb = new StringBuilder("CONNECT " +
				remoteHost + ":" + remotePort + " HTTP/1.1\r\n");
		if (args.length > 5) {
			sb.append("Proxy-Authorization: Basic " + Base64.getEncoder().
					encodeToString(args[5].getBytes()) + "\r\n");
		}
		sb.append("\r\n");
		byte[] request = sb.toString().getBytes();
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			connector.add(() -> new TunnelConnection(connector,
					proxyHost, proxyPort, request), localPort);
			Log.i(String.format("ProxyTunnel Started (%s->%s:%s) via %s:%s",
					"" + localPort, remoteHost, "" + remotePort,
					proxyHost, "" + proxyPort));
			connector.doEvents();
		} catch (IOException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("ProxyTunnel Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}