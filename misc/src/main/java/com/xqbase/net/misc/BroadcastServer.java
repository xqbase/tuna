package com.xqbase.net.misc;

import java.util.LinkedHashSet;

import com.xqbase.net.Connection;
import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.ListenerFactory;
import com.xqbase.net.ServerConnection;

class BroadcastListener implements Listener {
	private LinkedHashSet<BroadcastListener> listeners = new LinkedHashSet<>();
	private boolean noEcho;
	private Handler handler;

	public BroadcastListener(LinkedHashSet<BroadcastListener> listeners, boolean noEcho) {
		this.listeners = listeners;
		this.noEcho = noEcho;
	}

	@Override
	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		for (BroadcastListener listener : listeners.toArray(new BroadcastListener[0])) {
			// "listener.onDisconnect()" might change "listeners"
			if (!noEcho || listener != this) {
				listener.handler.send(b, off, len);
			}
		}
	}

	@Override
	public void onConnect() {
		listeners.add(this);
	}

	@Override
	public void onDisconnect() {
		listeners.remove(this);
	}
}

/**
 * A {@link ServerConnection} which sends received data
 * from one {@link Connection} to all its accepted connections.<p>
 *
 * Note that all its accepted connections will be closed automatically
 * when it is removed from a connector.
 */
public class BroadcastServer implements ListenerFactory {
	private LinkedHashSet<BroadcastListener> listeners = new LinkedHashSet<>();
	private boolean noEcho;

	public BroadcastServer() {
		this(false);
	}

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
	public Listener onAccept() {
		return new BroadcastListener(listeners, noEcho);
	}
}