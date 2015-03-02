package com.xqbase.tuna;

/**
 * Provides a wrapper for a {@link Connection} and a {@link ConnectionHandler}.<p>
 *
 * A <b>ConnectionFilter</b> can filter sent data and disconnecting signals
 * from the application side to the network side,
 * or filter received data, connecting and disconnecting events
 * from the network side to the application side.
 */
public class ConnectionFilter extends ConnectionWrapper implements ConnectionHandler {
	private ConnectionHandler handler;

	protected ConnectionFilter() {
		super(null);
	}

	public ConnectionHandler getHandler() {
		return handler;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	/** Wraps sent data, from the application side to the network side */
	@Override
	public void send(byte[] b, int off, int len) {
		handler.send(b, off, len);
	}

	/** Wraps buffer size events, from the application side to the network side */
	@Override
	public void setBufferSize(int bufferSize) {
		handler.setBufferSize(bufferSize);
	}

	/** Wraps active disconnecting events, from the application side to the network side */
	@Override
	public void disconnect() {
		handler.disconnect();
	}
}