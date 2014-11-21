package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import com.xqbase.net.Connection.Event;

/**
 * The encapsulation of {@link Selector},
 * which makes {@link Connection} and {@link ServerConnection} working.<p>
 */
public class Connector implements AutoCloseable {
	private static final Event INTERRUPTED = new Connection().new Event(0);
	/** Maximum buffer size. {@link #setBufferSize(int)} should not exceed this value */
	public static final int MAX_BUFFER_SIZE = 32768;

	/**
	 * The interface that can register a {@link Connection} or {@link ServerConnection}
	 * as a Event Listener, where events will be raised by {@link Connector#doEvents()}
	 */
	public static interface Listener {
		/** The method that will be called by {@link Connector#doEvents()} */
		public void onEvent();
	}

	private Selector selector;
	private boolean interrupted = false;
	private LinkedHashSet<Listener> listeners = new LinkedHashSet<>();
	private int bufferSize = MAX_BUFFER_SIZE;
	private byte[] buffer = new byte[MAX_BUFFER_SIZE];
	private ArrayList<FilterFactory> filterFactories = new ArrayList<>();
	private Consumer<Event> events = event -> {
		if (event == INTERRUPTED) {
			interrupted = true;
			return;
		}
		Connection connection = event.getConnection();
		if (!connection.isOpen()) {
			return;
		}
		int type = event.getType();
		if (type >= 0) {
			connection.netFilter.onEvent(event);
		}
		if (type <= 0) {
			connection.close();
			// Call "close()" before "onDisconnect()",
			// and recursive "disconnect()" can be avoided.
			connection.netFilter.onDisconnect();
			if (connection instanceof Listener) {
				listeners.remove(connection);
			}
		}
	};
	private ConcurrentLinkedQueue<Event> eventQueue = new ConcurrentLinkedQueue<>();

	{
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void add(Connection connection, int ops) {
		connection.events = events;
		connection.concurrentEvents = event -> {
			eventQueue.add(event);
			selector.wakeup();
		};
		try {
			connection.selectionKey = connection.socketChannel.
					register(selector, ops, connection);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (connection instanceof Listener) {
			listeners.add((Listener) connection);
		}
		connection.appendFilters(filterFactories);
	}

	/**
	 * Registers a <b>ServerConnection</b>
	 * 
	 * @param serverConnection - The ServerConnection to register.
	 * @see #remove(ServerConnection)
	 */
	public void add(ServerConnection serverConnection) {
		try {
			serverConnection.selectionKey = serverConnection.serverSocketChannel.
					register(selector, SelectionKey.OP_ACCEPT, serverConnection);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (serverConnection instanceof Listener) {
			listeners.add((Listener) serverConnection);
		}
	}

	/**
	 * Unregisters and closes a <b>ServerConnection</b>
	 * 
	 * @param serverConnection - The ServerConnection to unregister and close.
	 * @see #add(ServerConnection)
	 */
	public void remove(ServerConnection serverConnection) {
		if (serverConnection.selectionKey.isValid()) {
			serverConnection.close();
			if (serverConnection instanceof Listener) {
				listeners.remove(serverConnection);
			}
		}
	}

	/**
	 * @return An {@link ArrayList} of {@link FilterFactory}s,  to create a series of
	 *         {@link Filter}s and append into the end of filter chain when a
	 *         {@link Connection} connected, or accepted after
	 *         {@link ServerConnection#getFilterFactories()} takes effect.
	 * @see ServerConnection#getFilterFactories()
	 */
	public ArrayList<FilterFactory> getFilterFactories() {
		return filterFactories;
	}

	/**
	 * @return The buffer size of the Connector, shared by all Connections.
	 * @see #setBufferSize(int)
	 */
	public int getBufferSize() {
		return bufferSize;
	}

	/**
	 * @param bufferSize - Buffer size of every Connection, shared by all Connections.
	 * @see #getBufferSize()
	 */
	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	/** Consume events until interrupted */
	public void doEvents() {
		while (doEvents(-1) >= 0) {/**/}
	}

	/**
	 * Consumes all events raised by registered Connections and ServerConnections,
	 * including network events (accept/connect/read/write) and user-defined events.<p>
	 *
	 * @param timeout Block for up to timeout milliseconds, or -1 to block indefinitely,
	 *        or 0 without blocking. 
	 * @return <b>> 0</b> if NETWORK events consumed;<br>
	 *         <b>== 0</b> if no NETWORK events raised,
	 *         whether or not user-defined events raised;<br>
	 *         <b>< 0</b> if interrupted.
	 */
	public int doEvents(long timeout) {
		int keySize;
		try {
			keySize = timeout == 0 ? selector.selectNow() :
					timeout < 0 ? selector.select() : selector.select(timeout);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (interrupted) {
			interrupted = false;
			return -1;
		}

		Set<SelectionKey> selectedKeys = selector.selectedKeys();
		for (SelectionKey key : selectedKeys) {
			if (!key.isValid()) {
				continue;
			}
			if (key.isAcceptable()) {
				ServerConnection serverConnection = (ServerConnection) key.attachment();
				SocketChannel socketChannel;
				try {
					socketChannel = serverConnection.serverSocketChannel.accept();
					if (socketChannel == null) {
						continue;
					}
					socketChannel.configureBlocking(false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				Connection connection = serverConnection.createConnection();
				connection.socketChannel = socketChannel;
				connection.appendFilters(serverConnection.getFilterFactories());
				add(connection, SelectionKey.OP_READ);
				connection.finishConnect();
				continue;
			}
			Connection connection = (Connection) key.attachment();
			try {
				if (key.isReadable()) {
					int bytesRead = connection.socketChannel.
							read(ByteBuffer.wrap(buffer, 0, bufferSize));
					if (bytesRead > 0) {
						connection.netFilter.onRecv(buffer, 0, bytesRead);
					} else if (bytesRead < 0) {
						connection.dispatchEvent(-1);
					}
				} else if (key.isWritable()) {
					connection.write();
				} else { // key.isConnectable()
					if (connection.socketChannel.finishConnect()) {
						connection.finishConnect();
					}
				}
			} catch (IOException e) {
				connection.dispatchEvent(-1);
			}
		}
		selectedKeys.clear();

		Event event;
		while ((event = eventQueue.poll()) != null) {
			events.accept(event);
		}
		for (Listener listener : listeners.toArray(new Listener[0])) {
			// "listener.onEvent()" might change "listeners" 
			listener.onEvent();
		}
		return keySize;
	}

	public void interrupt() {
		eventQueue.add(INTERRUPTED);
		selector.wakeup();
	}

	/**
	 * Registers a {@link Connection} and connects to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 * @see #connect(Connection, InetSocketAddress)
	 */
	public void connect(Connection connection,
			String host, int port) throws IOException {
		connect(connection, new InetSocketAddress(host, port));
	}

	private static void closeSocketChannel(SocketChannel
			socketChannel) throws IOException {
		socketChannel.close();
	}

	/**
	 * registers a {@link Connection} and connects to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 * @see #connect(Connection, String, int)
	 */
	public void connect(Connection connection,
			InetSocketAddress remote) throws IOException {
		SocketChannel socketChannel = SocketChannel.open();
		socketChannel.configureBlocking(false);
		try {
			socketChannel.connect(remote);
		} catch (IOException e) {
			// Evade resource leak warning
			closeSocketChannel(socketChannel);
			throw e;
		}
		connection.socketChannel = socketChannel;
		connection.startConnect();
		add(connection, SelectionKey.OP_CONNECT);
	}

	/**
	 * Unregisters, closes all <b>Connection</b>s and <b>ServerConnection</b>s,
	 * then closes the Connector itself.
	 */
	@Override
	public void close() {
		for (FilterFactory filterFactory : filterFactories) {
			if (filterFactory instanceof AutoCloseable) {
				try {
					((AutoCloseable) filterFactory).close();
				} catch (Exception e) {/**/}
			}
		}
		for (SelectionKey key : selector.keys()) {
			Object o = key.attachment();
			if (o instanceof ServerConnection) {
				((ServerConnection) o).close();
			} else {
				((Connection) o).close();
			}
		}
		try {
			selector.close();
		} catch (IOException e) {/**/}
	}
}