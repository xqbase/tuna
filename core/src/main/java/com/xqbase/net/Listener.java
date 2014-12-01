package com.xqbase.net;

public interface Listener {
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

	/** @param handler */
	public default void setHandler(Handler handler) {/**/}

	public default Listener appendFilter(Filter filter) {
		return new Listener() {
			private Handler handler;

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

			@Override
			public void setHandler(Handler handler) {
				this.handler = new Handler() {
					@Override
					public void send(byte[] b, int off, int len) {
						handler.send(b, off, len);
					}

					@Override
					public void disconnect() {
						handler.disconnect();
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
					public void blockRecv(boolean blocked) {
						handler.blockRecv(blocked);
					}

					@Override
					public void execute(Runnable command) {
						handler.execute(command);
					}
				};
			}
		};
	}
}