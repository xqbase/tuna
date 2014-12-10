package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface Connector {
	public static final String ANY_LOCAL_ADDRESS =
			new InetSocketAddress(0).getAddress().getHostAddress();

	public interface Closeable extends AutoCloseable {
		@Override
		public void close();
	}

	/**
	 * Registers a {@link ServerConnection}
	 *
	 * @param serverConnection
	 * @param port
	 * @return a {@link Closeable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all
	 *         <code>serverConnection</code>s when closing.
	 */
	public default Closeable add(ServerConnection serverConnection,
			int port) throws IOException {
		return add(serverConnection, ANY_LOCAL_ADDRESS, port);
	}

	/**
	 * Registers a {@link ServerConnection}
	 *
	 * @param serverConnection
	 * @param host
	 * @param port
	 * @return a {@link Closeable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all
	 *         <code>serverConnection</code>s when closing.
	 */
	public Closeable add(ServerConnection serverConnection,
			String host, int port) throws IOException;

	/**
	 * Registers and connects a {@link Connection} to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 */
	public void connect(Connection connection,
			String host, int port) throws IOException;
}