package com.xqbase.tuna.misc;

import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ServerConnection;

class BroadcastConnection implements Connection {
	private static final ConnectionHandler[] EMPTY_HANDLER = new ConnectionHandler[0];

	private LinkedHashSet<ConnectionHandler> handlers;
	private boolean noEcho;
	private ConnectionHandler handler;

	public BroadcastConnection(LinkedHashSet<ConnectionHandler> handlers, boolean noEcho) {
		this.handlers = handlers;
		this.noEcho = noEcho;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		for (ConnectionHandler handler_ : handlers.toArray(EMPTY_HANDLER)) {
			// "connection.onDisconnect()" might change "handlers"
			if (!noEcho || handler_ != handler) {
				handler_.send(b, off, len);
			}
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		handlers.add(handler);
	}

	@Override
	public void onDisconnect() {
		handlers.remove(handler);
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
	private LinkedHashSet<ConnectionHandler> handlers = new LinkedHashSet<>();
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
		return new BroadcastConnection(handlers, noEcho);
	}
}