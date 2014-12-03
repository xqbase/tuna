package com.xqbase.net;

class FilteredHandler implements Handler {
	private Filter filter;

	FilteredHandler(Filter filter) {
		this.filter = filter;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		filter.send(b, off, len);
	}

	@Override
	public void disconnect() {
		filter.disconnect();
	}

	@Override
	public void blockRecv(boolean blocked) {
		filter.blockRecv(blocked);
	}

	@Override
	public String getLocalAddr() {
		return filter.getLocalAddr();
	}

	@Override
	public int getLocalPort() {
		return filter.getLocalPort();
	}

	@Override
	public String getRemoteAddr() {
		return filter.getRemoteAddr();
	}

	@Override
	public int getRemotePort() {
		return filter.getRemotePort();
	}

	@Override
	public void execute(Runnable command) {
		filter.execute(command);
	}
}

class FilteredListener implements Listener {
	private Filter filter;
	private Listener listener;

	FilteredListener(Filter filter, Listener listener) {
		this.filter = filter;
		this.listener = listener;
		filter.setListener(listener);
	}

	@Override
	public void setHandler(Handler handler) {
		filter.setHandler(handler);
		listener.setHandler(filter);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		filter.onRecv(b, off, len);
	}

	@Override
	public void onSend(boolean queued) {
		filter.onSend(queued);
	}

	@Override
	public void onConnect() {
		filter.onConnect();
	}

	@Override
	public void onDisconnect() {
		filter.onDisconnect();
	}
}

public interface Listener {
	/** @param handler */
	public default void setHandler(Handler handler) {/**/}
	/** 
	 * Consumes received data in the APPLICATION end of the connection.
	 *
	 * @param b
	 * @param off
	 * @param len
	 */
	public default void onRecv(byte[] b, int off, int len) {/**/}
	/** 
	 * Consumes queued/completed sending events in the APPLICATION end of the connection.
	 *
	 * @param queued
	 */
	public default void onSend(boolean queued) {/**/}
	/** Consumes connecting events in the APPLICATION end of the connection. */
	public default void onConnect() {/**/}
	/** Consumes passive disconnecting events in the APPLICATION end of the connection. */
	public default void onDisconnect() {/**/}

	/**
	 * Adds a {@link Filter} into the network end of the listener.
	 *
	 * @see Connector#getFilters()
	 * @see Server#getFilters()
	 */
	public default Listener appendFilter(Filter filter) {
		return new FilteredListener(filter, this);
	}
}