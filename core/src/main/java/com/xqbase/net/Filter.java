package com.xqbase.net;

/**
 * Provides a filtering task on a {@link Connection}.<p>
 * 
 * A <b>Filter</b> can filter sent data from the application side to the network side,
 * or filter received data and events (including connecting and disconnecting events)
 * from the network side to the application side.
 */
public class Filter implements Listener, Handler {
	Handler handler;
	Filter netFilter, appFilter;

	/** Filters received data, from the network side to the application side */
	@Override
	public void onRecv(byte[] b, int off, int len) {
		appFilter.onRecv(b, off, len);
	}

	/** Filters queued/completed sending events, from the network side to the application side */
	@Override
	public void onSend(boolean queued) {
		appFilter.onSend(queued);
	}

	/** Filters connecting events, from the network side to the application side */
	@Override
	public void onConnect() {
		appFilter.onConnect();
	}

	/** Filters passive disconnecting events, from the network side to the application side */
	@Override
	public void onDisconnect() {
		appFilter.onDisconnect();
	}

	/** Filters sent data, from the application side to the network side */
	@Override
	public void send(byte[] b, int off, int len) {
		netFilter.send(b, off, len);
	}

	/** Filters active disconnecting events, from the application side to the network side */
	@Override
	public void disconnect() {
		netFilter.disconnect();
	}

	// Following methods are just wraps of "handler"

	@Override
	public String getLocalAddr() {
		return handler.getLocalAddr();
	}

	@Override
	public int getLocalPort() {
		return handler.getLocalPort();
	}

	@Override
	public String getRemoteAddr() {
		return handler.getRemoteAddr();
	}

	@Override
	public int getRemotePort() {
		return handler.getRemotePort();
	}

	@Override
	public int getQueueSize() {
		return handler.getQueueSize();
	}

	@Override
	public long getBytesRecv() {
		return handler.getBytesRecv();
	}

	@Override
	public long getBytesSent() {
		return handler.getBytesSent();
	}

	@Override
	public void invokeLater(Runnable runnable) {
		handler.invokeLater(runnable);
	}
}