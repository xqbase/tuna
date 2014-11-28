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
	ListenerFactory listenerFactory;
	ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
	SelectionKey selectionKey;
	Connector connector;

	/** 
	 * Opens a listening port and binds to a given address.
	 * @param addr - The IP address to bind and the port to listen. 
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	ServerConnection(ListenerFactory listenerFactory,
			InetSocketAddress addr) throws IOException {
		this.listenerFactory = listenerFactory;
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