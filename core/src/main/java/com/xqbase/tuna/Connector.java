package com.xqbase.tuna;

import java.io.IOException;
import java.net.InetSocketAddress;

public interface Connector {
	@FunctionalInterface
	public static interface Closeable extends AutoCloseable {
		@Override
		public void close();
	}

	/**
	 * Registers a {@link ServerConnection}
	 *
	 * @param serverConnection
	 * @return a {@link Closeable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all
	 *         <code>serverConnection</code>s when closing.
	 */
	public default Closeable add(ServerConnection serverConnection,
			String host, int port) throws IOException {
		return add(serverConnection, new InetSocketAddress(host, port));
	}

	/**
	 * Registers a {@link ServerConnection}
	 *
	 * @return a {@link Closeable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all
	 *         <code>serverConnection</code>s when closing.
	 */
	public default Closeable add(ServerConnection serverConnection,
			int port) throws IOException {
		return add(serverConnection, new InetSocketAddress(port));
	}

	/**
	 * Registers a {@link ServerConnection}
	 *
	 * @return a {@link Closeable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all
	 *         <code>serverConnection</code>s when closing.
	 */
	public Closeable add(ServerConnection serverConnection,
			InetSocketAddress socketAddress) throws IOException;

	/**
	 * Registers and connects a {@link Connection} to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 */
	public default void connect(Connection connection,
			String host, int port) throws IOException {
		connect(connection, InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * Registers and connects a {@link Connection} to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 */
	public void connect(Connection connection,
			InetSocketAddress socketAddress) throws IOException;
}