package com.xqbase.net;

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
		filter.setListener(this);
		setHandler(filter);
		return new Listener() {
			@Override
			public void setHandler(Handler handler) {
				filter.setHandler(handler);
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
		};
	}
}