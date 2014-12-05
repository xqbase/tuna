package com.xqbase.net;

public interface Connection {
	/** @param handler */
	public default void setHandler(ConnectionHandler handler) {/**/}
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

	/** Adds a {@link ConnectionWrapper} as a filter into the network end of the connection. */
	public default Connection appendFilter(ConnectionWrapper filter) {
		filter.setConnection(this);
		setHandler(filter);
		return new Connection() {
			@Override
			public void setHandler(ConnectionHandler handler) {
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