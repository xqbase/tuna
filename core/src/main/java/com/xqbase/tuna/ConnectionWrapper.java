package com.xqbase.tuna;

public abstract class ConnectionWrapper implements Connection {
	Connection connection;

	protected ConnectionWrapper(Connection connection) {
		this.connection = connection;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		connection.setHandler(handler);
	}

	/** Wraps received data, from the network side to the application side */
	@Override
	public void onRecv(byte[] b, int off, int len) {
		connection.onRecv(b, off, len);
	}

	/** Wraps queue events, from the network side to the application side */
	@Override
	public void onQueue(int size) {
		connection.onQueue(size);
	}

	/** Wraps connecting events, from the network side to the application side */
	@Override
	public void onConnect(ConnectionSession session) {
		connection.onConnect(session);
	}

	/** Wraps passive disconnecting events, from the network side to the application side */
	@Override
	public void onDisconnect() {
		connection.onDisconnect();
	}
}