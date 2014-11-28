package com.xqbase.net;

public interface Handler extends EventQueue {
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
	 * Closes the connection actively.<p>
	 *
	 * If <code>send()</code> is called before <code>disconnect()</code>,
	 * the connection will not be closed until all queued data sent out. 
	 */
	public void disconnect();

	/** @return Local IP address of the Connection. */
	public String getLocalAddr();
	/** @return Local port of the Connection. */
	public int getLocalPort();
	/** @return Remote IP address of the Connection. */
	public String getRemoteAddr();
	/** @return Remote port of the Connection. */
	public int getRemotePort();
	/**
	 * Block or unblock receiving
	 *
	 * @param blocked
	 */
	public default void blockRecv(boolean blocked) {/**/}
	/** @return Queue Size */
	public int getQueueSize();
	/** @return Received Bytes */
	public long getBytesRecv();
	/** @return Sent Bytes */
	public long getBytesSent();
}