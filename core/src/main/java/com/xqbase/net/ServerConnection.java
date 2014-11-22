package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;

/**
 * The encapsulation of a {@link ServerSocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Server Socket 
 */
public class ServerConnection {
	/** @return The {@link Connection} accepted by this ServerConnection. */
	protected Connection createConnection() {
		return new Connection();
	}

	ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
	SelectionKey selectionKey;
	Connector connector;

	/**
	 * Opens a listening port and binds to 0.0.0.0.
	 * @param port - The port to listen.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	public ServerConnection(int port) throws IOException {
		this(new InetSocketAddress(port));
	}

	/**
	 * Opens a listening port and binds to a given address.
	 * @param host - The IP address to bind. 
	 * @param port - The port to listen.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	public ServerConnection(String host, int port) throws IOException {
		this(new InetSocketAddress(host, port));
	}

	/** 
	 * Opens a listening port and binds to a given address.
	 * @param addr - The IP address to bind and the port to listen. 
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	public ServerConnection(InetSocketAddress addr) throws IOException {
		serverSocketChannel.configureBlocking(false);
		try {
			serverSocketChannel.socket().bind(addr);
		} catch (IOException e) {
			serverSocketChannel.close();
			throw e;
		}
	}

	private ArrayList<FilterFactory> filterFactories = new ArrayList<>();

	/**
	 * @return An {@link ArrayList} of {@link FilterFactory}s, to create a series of
	 *         {@link Filter}s and append into the end of filter chain when a
	 *         {@link Connection} accepted, before
	 *         {@link Connector#getFilterFactories()} takes effect.
	 * @see Connector#getFilterFactories()
	 */
	public ArrayList<FilterFactory> getFilterFactories() {
		return filterFactories;
	}

	/** Invokes a {@link Runnable} in main thread */
	public void invokeLater(Runnable runnable) {
		connector.invokeLater(runnable);
	}

	protected void onClose() {/**/}

	void close() {
		for (FilterFactory filterFactory : filterFactories) {
			if (filterFactory instanceof AutoCloseable) {
				try {
					((AutoCloseable) filterFactory).close();
				} catch (Exception e) {/**/}
			}
		}
		selectionKey.cancel();
		try {
			serverSocketChannel.close();
		} catch (IOException e) {/**/}
	}
}