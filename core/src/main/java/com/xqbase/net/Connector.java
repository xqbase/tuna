package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import com.xqbase.net.util.ByteArrayQueue;

/**
 * The encapsulation of a {@link SocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Socket.
 */
class Client {
	private static final int STATUS_IDLE = 0;
	private static final int STATUS_BUSY = 1;
	private static final int STATUS_DISCONNECTING = 2;
	private static final int STATUS_CLOSED = 3;

	boolean blocked = false;
	int status = STATUS_IDLE;
	long bytesRecv = 0, bytesSent = 0;
	ByteArrayQueue queue = new ByteArrayQueue();
	InetSocketAddress local = new InetSocketAddress(0),
			remote = new InetSocketAddress(0);
	SocketChannel socketChannel;
	SelectionKey selectionKey;
	Connection connection;
	Connector connector;

	Client(Connection connection) {
		this.connection = connection;
		connection.setHandler(new ConnectionHandler() {
			@Override
			public void send(byte[] b, int off, int len) {
				write(b, off, len);
			}

			@Override
			public void disconnect() {
				if (status == STATUS_IDLE) {
					finishClose();
					connector.activeDisconnectCount ++;
				} else if (status == STATUS_BUSY) {
					status = STATUS_DISCONNECTING;
				}
			}

			@Override
			public void blockRecv(boolean blocked_) {
				blocked = blocked_;
				if (status != STATUS_CLOSED) {
					interestOps();
				}
			}

			@Override
			public String getLocalAddr() {
				return local.getAddress().getHostAddress();
			}

			@Override
			public int getLocalPort() {
				return local.getPort();
			}

			@Override
			public String getRemoteAddr() {
				return remote.getAddress().getHostAddress();
			}

			@Override
			public int getRemotePort() {
				return remote.getPort();
			}

			@Override
			public int getQueueSize() {
				return queue.length();
			}

			@Override
			public long getBytesRecv() {
				return bytesRecv;
			}

			@Override
			public long getBytesSent() {
				return bytesSent;
			}

			@Override
			public void execute(Runnable runnable) {
				connector.execute(runnable);
			}
		});
	}

	void interestOps() {
		selectionKey.interestOps((blocked ? 0 : SelectionKey.OP_READ) |
				(status == STATUS_IDLE ? 0 : SelectionKey.OP_WRITE));
	}

	void write() throws IOException {
		boolean queued = false;
		while (queue.length() > 0) {
			queued = true;
			int bytesWritten = socketChannel.write(ByteBuffer.wrap(queue.array(),
					queue.offset(), queue.length()));
			if (bytesWritten == 0) {
				interestOps();
				return;
			}
			bytesSent += bytesWritten;
			connector.totalBytesSent += bytesWritten;
			queue.remove(bytesWritten);
			connector.totalQueueSize -= bytesWritten;
		}
		if (queued) {
			connection.onSend(false);
		}
		if (status == STATUS_DISCONNECTING) {
			finishClose();
			connector.activeDisconnectCount ++;
		} else {
			status = STATUS_IDLE;
			interestOps();
		}
	}

	void write(byte[] b, int off, int len) {
		if (status != STATUS_IDLE) {
			if (queue.length() == 0) {
				connection.onSend(true);
			}
			queue.add(b, off, len);
			connector.totalQueueSize += len;
			return;
		}
		int bytesWritten;
		try {
			bytesWritten = socketChannel.write(ByteBuffer.wrap(b, off, len));
		} catch (IOException e) {
			startClose();
			return;
		}
		connector.totalBytesSent += bytesWritten;
		if (bytesWritten < len) {
			queue.add(b, off + bytesWritten, len - bytesWritten);
			connector.totalQueueSize += len - bytesWritten;
			connection.onSend(true);
			status = STATUS_BUSY;
			interestOps();
		}
	}

	void startConnect() {
		status = STATUS_BUSY;
	}

	void finishConnect() {
		local = ((InetSocketAddress) socketChannel.
				socket().getLocalSocketAddress());
		remote = ((InetSocketAddress) socketChannel.
				socket().getRemoteSocketAddress());
		connection.onConnect();
	}

	void startClose() {
		if (status == STATUS_CLOSED) {
			return;
		}
		finishClose();
		connector.passiveDisconnectCount ++;
		// Call "close()" before "onDisconnect()"
		// to avoid recursive "disconnect()".
		connection.onDisconnect();
	}

	void finishClose() {
		connector.totalQueueSize -= queue.length();
		status = STATUS_CLOSED;
		selectionKey.cancel();
		try {
			socketChannel.close();
		} catch (IOException e) {/**/}
	}

	boolean isOpen() {
		return status != STATUS_CLOSED;
	}

	boolean isBusy() {
		return status != STATUS_IDLE;
	}
}

/**
 * The encapsulation of a {@link ServerSocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Server Socket 
 */
class Server {
	ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
	SelectionKey selectionKey;
	ServerConnection serverConnection;
	Connector connector;

	/** 
	 * Opens a listening port and binds to a given address.
	 * @param addr - The IP address to bind and the port to listen. 
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	Server(ServerConnection serverConnection,
			InetSocketAddress addr) throws IOException {
		this.serverConnection = serverConnection;
		serverSocketChannel.configureBlocking(false);
		try {
			serverSocketChannel.socket().bind(addr);
		} catch (IOException e) {
			serverSocketChannel.close();
			throw e;
		}
	}

	void close() {
		selectionKey.cancel();
		try {
			serverSocketChannel.close();
		} catch (IOException e) {/**/}
	}
}

/**
 * The encapsulation of {@link Selector},
 * which makes {@link Client} and {@link Server} working.<p>
 */
public class Connector implements Executor, AutoCloseable {
	private static final int BUFFER_SIZE = 32768;

	private Selector selector;
	private boolean interrupted = false;
	private byte[] buffer = new byte[BUFFER_SIZE];
	private ConcurrentLinkedQueue<Runnable> eventQueue = new ConcurrentLinkedQueue<>();

	{
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void add(Client client, int ops) {
		client.connector = this;
		try {
			client.selectionKey = client.socketChannel.
					register(selector, ops, client);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Registers a {@link Client} and connects to a remote address
	 *
	 * @throws IOException If the remote address is invalid.
	 * @see #connect(Connection, InetSocketAddress)
	 */
	public void connect(Connection connection,
			String host, int port) throws IOException {
		connect(connection, new InetSocketAddress(host, port));
	}

	private static void closeSocketChannel(SocketChannel socketChannel)
			throws IOException {
		socketChannel.close();
	}

	/**
	 * registers a {@link Client} and connects to a remote address
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
		Client client = new Client(connection);
		client.socketChannel = socketChannel;
		client.startConnect();
		add(client, SelectionKey.OP_CONNECT);
	}

	/**
	 * Registers a {@link ServerConnection}
	 * 
	 * @param serverConnection
	 * @see #add(ServerConnection, InetSocketAddress) 
	 */
	public AutoCloseable add(ServerConnection serverConnection,
			String host, int port) throws IOException {
		return add(serverConnection, new InetSocketAddress(host, port));
	}

	/**
	 * Registers a {@link ServerConnection}
	 * 
	 * @param serverConnection
	 * @see #add(ServerConnection, InetSocketAddress) 
	 */
	public AutoCloseable add(ServerConnection serverConnection, int port) throws IOException {
		return add(serverConnection, new InetSocketAddress(port));
	}

	/**
	 * Registers a {@link ServerConnection}
	 * 
	 * @param serverConnection
	 * @return an {@link AutoCloseable} that will unregister the <code>serverConnection</code>.
	 *         The connector will automatically unregister all <code>serverConnection</code>s when closing. 
	 */
	public AutoCloseable add(ServerConnection serverConnection,
			InetSocketAddress addr) throws IOException {
		Server server = new Server(serverConnection, addr);
		serverConnection.setExecutor(this);
		server.connector = this;
		try {
			server.selectionKey = server.serverSocketChannel.
					register(selector, SelectionKey.OP_ACCEPT, server);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return () -> {
			if (server.selectionKey.isValid()) {
				server.close();
			}
		};
	}

	/** Consume events until interrupted */
	public void doEvents() {
		while (!isInterrupted()) {
			doEvents(-1);
		}
	}

	private void invokeQueue() {
		Runnable runnable;
		while ((runnable = eventQueue.poll()) != null) {
			runnable.run();
		}
	}

	/**
	 * Consumes all events raised by registered Clients and Servers,
	 * including network events (accept/connect/read/write) and user-defined events.<p>
	 *
	 * @param timeout Block for up to timeout milliseconds, or -1 to block indefinitely,
	 *        or 0 without blocking.
	 * @return <b>true</b> if NETWORK events consumed;<br>
	 *         <b>false</b> if no NETWORK events raised,
	 *         whether or not user-defined events raised.<br>
	 */
	public boolean doEvents(long timeout) {
		int keySize;
		try {
			keySize = timeout == 0 ? selector.selectNow() :
					timeout < 0 ? selector.select() : selector.select(timeout);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		if (keySize == 0) {
			invokeQueue();
			return false;
		}

		Set<SelectionKey> selectedKeys = selector.selectedKeys();
		for (SelectionKey key : selectedKeys) {
			if (!key.isValid()) {
				continue;
			}
			if (key.isAcceptable()) {
				Server server = (Server) key.attachment();
				SocketChannel socketChannel;
				try {
					socketChannel = server.serverSocketChannel.accept();
					if (socketChannel == null) {
						continue;
					}
					socketChannel.configureBlocking(false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				Client client = new Client(server.serverConnection.get());
				client.socketChannel = socketChannel;
				add(client, SelectionKey.OP_READ);
				client.finishConnect();
				acceptCount ++;
				continue;
			}
			Client client = (Client) key.attachment();
			try {
				if (key.isReadable()) {
					int bytesRead = client.socketChannel.
							read(ByteBuffer.wrap(buffer, 0, BUFFER_SIZE));
					if (bytesRead > 0) {
						client.connection.onRecv(buffer, 0, bytesRead);
						client.bytesRecv += bytesRead;
						totalBytesRecv += bytesRead;
						// may be closed by "onRecv"
						if (!key.isValid()) {
							continue;
						}
					} else if (bytesRead < 0) {
						client.startClose();
						// Disconnected, so skip onSend and onConnect
						continue;
					}
				}
				// may be both isReadable() and isWritable() ?
				if (key.isWritable()) {
					client.write();
				} else if (key.isConnectable() && client.socketChannel.finishConnect()) {
					client.finishConnect();
					connectCount ++;
					// "onConnect()" might call "disconnect()"
					if (client.isOpen() && client.isBusy()) {
						client.write();
					}
				}
			} catch (IOException e) {
				client.startClose();
			}
		}
		selectedKeys.clear();
		invokeQueue();
		return true;
	}

	/** 
	 * Executes a command in main thread.<br>
	 * <b>Must be called in another thread.</b>
	 *
	 * @see #interrupt()
	 */
	@Override
	public void execute(Runnable command) {
		eventQueue.offer(command);
		selector.wakeup();
	}

	/**
	 * Interrupts {@link #doEvents()} or {@link #doEvents(long)}.<br>
	 * <b>Must be called in another thread.</b>
	 *
	 * @see #execute(Runnable)
	 */
	public void interrupt() {
		execute(() -> interrupted = true);
	}

	/**
	 * Tests whether the connector is interrupted,
	 * and then the "interrupted" status is cleared.
	 */
	public boolean isInterrupted() {
		boolean interrupted_ = interrupted;
		interrupted = false;
		return interrupted_;
	}

	/**
	 * Unregisters, closes all <b>Client</b>s and <b>Server</b>s,
	 * then closes the Connector itself.
	 */
	@Override
	public void close() {
		for (SelectionKey key : selector.keys()) {
			Object o = key.attachment();
			if (o instanceof Server) {
				((Server) o).close();
			} else {
				((Client) o).finishClose();
				activeDisconnectCount ++;
			}
		}
		try {
			selector.close();
		} catch (IOException e) {/**/}
	}

	private int acceptCount = 0, connectCount = 0;
	int activeDisconnectCount = 0, passiveDisconnectCount = 0, totalQueueSize = 0;
	private long totalBytesRecv = 0;
	long totalBytesSent = 0;

	public int getAcceptCount() {
		return acceptCount;
	}

	public int getConnectCount() {
		return connectCount;
	}

	public int getActiveDisconnectCount() {
		return activeDisconnectCount;
	}

	public int getPassiveDisconnectCount() {
		return passiveDisconnectCount;
	}

	public int getTotalQueueSize() {
		return totalQueueSize;
	}

	public long getTotalBytesRecv() {
		return totalBytesRecv;
	}

	public long getTotalBytesSent() {
		return totalBytesSent;
	}
}