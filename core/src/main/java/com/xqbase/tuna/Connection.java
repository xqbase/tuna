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
	 * @param size total queue size, 0 for complete sending
	 */
	public default void onQueue(int size) {/**/}
	/**
	 * Consumes connecting events in the APPLICATION end of the connection.
	 *
	 * @param localAddr Local IP Address
	 * @param localPort Local Port
	 * @param remoteAddr Remote IP Address
	 * @param remotePort Remote Port
	 */
	public default void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {/**/}
	/** Consumes passive disconnecting events in the APPLICATION end of the connection. */
	public default void onDisconnect() {/**/}

	// TODO append filter after onConnect ?
	/** Adds a {@link ConnectionFilter} as a filter into the network end of the connection. */
	public default ConnectionWrapper appendFilter(ConnectionFilter filter) {
		filter.connection = this;
		return new ConnectionWrapper(filter) {
			@Override
			public void setHandler(ConnectionHandler handler) {
				super.setHandler(handler); // identical to "filter.setHandler(handler)"
				Connection.this.setHandler(filter);
			}
		};
	}
}