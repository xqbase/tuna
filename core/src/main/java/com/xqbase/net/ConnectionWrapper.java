package com.xqbase.net;

/**
 * Provides a wrapper for a {@link Connection} and a {@link ConnectionHandler}.<p>
 *
 * A <b>ConnectionWrapper</b> can filter sent data and disconnecting signals
 * from the application side to the network side,
 * or filter received data, connecting and disconnecting events
 * from the network side to the application side.
 */
public class ConnectionWrapper implements Connection, ConnectionHandler {
	private Connection connection;
	private ConnectionHandler handler;

	public void setConnection(Connection connection) {
		this.connection = connection;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	/** Wraps received data, from the network side to the application side */
	@Override
	public void onRecv(byte[] b, int off, int len) {
		connection.onRecv(b, off, len);
	}

	/** Wraps queue events, from the network side to the application side */
	@Override
	public void onQueue(int delta, int total) {
		connection.onQueue(delta, total);
	}

	/** Wraps connecting events, from the network side to the application side */
	@Override
	public void onConnect() {
		connection.onConnect();
	}

	/** Wraps passive disconnecting events, from the network side to the application side */
	@Override
	public void onDisconnect() {
		connection.onDisconnect();
	}

	/** Wraps sent data, from the application side to the network side */
	@Override
	public void send(byte[] b, int off, int len) {
		handler.send(b, off, len);
	}

	/** Wraps active disconnecting events, from the application side to the network side */
	@Override
	public void disconnect() {
		handler.disconnect();
	}

	@Override
	public void setBufferSize(int bufferSize) {
		handler.setBufferSize(bufferSize);
	}

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
	public Closeable postAtTime(Runnable runnable, long uptime) {
		return handler.postAtTime(runnable, uptime);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		handler.invokeLater(runnable);
	}

	@Override
	public void execute(Runnable runnable) {
		handler.execute(runnable);
	}

	/** Only for debug. */
	@Override
	public String toString() {
		return getLocalAddr() + ":" + getLocalPort() + "<->" +
				getRemoteAddr() + ":" + getRemotePort();
	}
}