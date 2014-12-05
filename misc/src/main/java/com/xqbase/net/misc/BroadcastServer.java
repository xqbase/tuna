package com.xqbase.net.misc;

import java.util.LinkedHashSet;

import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.Connection;
import com.xqbase.net.ServerConnection;

class BroadcastConnection implements Connection {
	private LinkedHashSet<BroadcastConnection> connections;
	private boolean noEcho;
	private ConnectionHandler handler;

	public BroadcastConnection(LinkedHashSet<BroadcastConnection> connections, boolean noEcho) {
		this.connections = connections;
		this.noEcho = noEcho;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		for (BroadcastConnection connection : connections.toArray(new BroadcastConnection[0])) {
			// "connection.onDisconnect()" might change connections
			if (!noEcho || connection != this) {
				connection.handler.send(b, off, len);
			}
		}
	}

	@Override
	public void onConnect() {
		connections.add(this);
	}

	@Override
	public void onDisconnect() {
		connections.remove(this);
	}
}

/**
 * A {@link ServerConnection} which sends received data
 * from one {@link Connection} to all its accepted connections.<p>
 *
 * Note that all its accepted connections will be closed automatically
 * when it is removed from a connector.
 */
public class BroadcastServer implements ServerConnection {
	private LinkedHashSet<BroadcastConnection> connections = new LinkedHashSet<>();
	private boolean noEcho;

	/**
	 * Creates a BroadcastServer
	 *
	 * @param noEcho - <code>true</code> if received data is not allowed to
	 *        send back to the original connection.
	 */
	public BroadcastServer(boolean noEcho) {
		this.noEcho = noEcho;
	}

	@Override
	public Connection get() {
		return new BroadcastConnection(connections, noEcho);
	}
}