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
}