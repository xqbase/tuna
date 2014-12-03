package com.xqbase.net;

/**
 * Provides a filtering task on a {@link Client}.<p>
 * 
 * A <b>Filter</b> can filter sent data from the application side to the network side,
 * or filter received data and events (including connecting and disconnecting events)
 * from the network side to the application side.
 */
public class Filter implements Listener, Handler {
	private Listener listener;
	private Handler handler;

	public void setListener(Listener listener) {
		this.listener = listener;
	}

	@Override
	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	/** Filters received data, from the network side to the application side */
	@Override
	public void onRecv(byte[] b, int off, int len) {
		listener.onRecv(b, off, len);
	}

	/** Filters queued/completed sending events, from the network side to the application side */
	@Override
	public void onSend(boolean queued) {
		listener.onSend(queued);
	}

	/** Filters connecting events, from the network side to the application side */
	@Override
	public void onConnect() {
		listener.onConnect();
	}

	/** Filters passive disconnecting events, from the network side to the application side */
	@Override
	public void onDisconnect() {
		listener.onDisconnect();
	}

	/** Filters sent data, from the application side to the network side */
	@Override
	public void send(byte[] b, int off, int len) {
		handler.send(b, off, len);
	}

	/** Filters active disconnecting events, from the application side to the network side */
	@Override
	public void disconnect() {
		handler.disconnect();
	}

	@Override
	public void blockRecv(boolean blocked) {
		handler.blockRecv(blocked);
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
	public void execute(Runnable command) {
		handler.execute(command);
	}
}