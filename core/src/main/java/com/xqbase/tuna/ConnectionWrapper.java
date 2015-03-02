package com.xqbase.tuna;

public class ConnectionWrapper implements Connection {
	Connection connection;

	public ConnectionWrapper(Connection connection) {
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
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		connection.onConnect(localAddr, localPort, remoteAddr, remotePort);
	}

	/** Wraps passive disconnecting events, from the network side to the application side */
	@Override
	public void onDisconnect() {
		connection.onDisconnect();
	}
}