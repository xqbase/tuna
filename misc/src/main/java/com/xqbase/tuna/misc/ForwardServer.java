package com.xqbase.tuna.misc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;

class PeerConnection implements Connection {
	PeerConnection peer;
	ConnectionHandler handler;

	PeerConnection(PeerConnection peer) {
		this.peer = peer;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer != null) {
			peer.handler.send(b, off, len);
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

class ForwardConnection extends PeerConnection {
	private ForwardServer forward;

	ForwardConnection(ForwardServer forward) {
		super(null);
		this.forward = forward;
	}

	@Override
	public void onConnect(ConnectionSession session) {
		peer = new PeerConnection(this);
		Connection connection = peer;
		for (Supplier<? extends ConnectionFilter> serverFilter : forward.serverFilters) {
			connection = connection.appendFilter(serverFilter.get());
		}
		try {
			forward.connector.connect(connection, forward.host, forward.port);
		} catch (IOException e) {
			peer = null;
			handler.disconnect();
		}
	}
}

/** A port redirecting server. */
public class ForwardServer implements ServerConnection {
	Connector connector;
	String host;
	int port;
	List<Supplier<? extends ConnectionFilter>> serverFilters = new ArrayList<>();

	@Override
	public Connection get() {
		return new ForwardConnection(this);
	}

	/**
	 * Creates a ForwardServer.
	 * @param connector - A connector which remote connections registered to.
	 * @param host - The remote (forwarded) host.
	 * @param port - The remote (forwarded) port.
	 */
	public ForwardServer(Connector connector, String host, int port) {
		this.connector = connector;
		this.host = host;
		this.port = port;
	}

	/** Append "ServerFilter"s to remote connections. */
	public void appendRemoteFilter(Supplier<? extends ConnectionFilter> serverFilter) {
		serverFilters.add(serverFilter);
	}
}