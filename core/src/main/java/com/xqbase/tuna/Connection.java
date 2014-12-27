package com.xqbase.tuna;

public interface Connection {
	public static final int MAX_BUFFER_SIZE = 32768;

	/**
	 * A {@link ConnectionHandler} will be set by {@link ConnectorImpl}
	 * when connection is ready to establish.
	 *
	 * @param handler
	 */
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
	 * Consumes queue (queued or completed sending) events in the APPLICATION end of the connection.
	 *
	 * @param delta change of queue size
	 * @param total queue size after change, 0 for complete sending
	 */
	public default void onQueue(int delta, int total) {/**/}
	/** Consumes connecting events in the APPLICATION end of the connection. */
	public default void onConnect() {/**/}
	/** Consumes passive disconnecting events in the APPLICATION end of the connection. */
	public default void onDisconnect() {/**/}

	// TODO append filter after onConnect ?
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
			public void onQueue(int delta, int total) {
				filter.onQueue(delta, total);
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