package com.xqbase.tuna;

public interface ConnectionHandler {
	/**
	 * Sends a sequence of bytes in the application end,
	 * equivalent to <code>send(b, 0, b.length).</code>
	 */
	public default void send(byte[] b) {
		send(b, 0, b.length);
	}
	/** Sends a sequence of bytes in the application end. */
	public void send(byte[] b, int off, int len);
	/**
	 * Set buffer size
	 *
	 * @param bufferSize buffer size, <code>0</code> to block receiving
	 */
	public void setBufferSize(int bufferSize);
	/**
	 * Closes the connection actively.<p>
	 *
	 * If <code>send()</code> is called before <code>disconnect()</code>,
	 * the connection will not be closed until all queued data sent out.
	 */
	public void disconnect();
	/** Closes the connection actively regardless queued data.<p> */
	public default void forceDisconnect() {
		disconnect();
	}
}