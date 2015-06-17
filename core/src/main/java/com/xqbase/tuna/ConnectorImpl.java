package com.xqbase.tuna;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.xqbase.tuna.util.ByteArrayQueue;

/**
 * The encapsulation of a {@link SocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Socket.
 */
class Client {
	private static final int
			STATUS_CLOSED = 0,
			STATUS_IDLE = 1,
			STATUS_BUSY = 2,
			STATUS_DISCONNECTING = 3;

	int bufferSize = Connection.MAX_BUFFER_SIZE;
	int status = STATUS_IDLE;
	boolean resolving = false;
	ByteArrayQueue queue = new ByteArrayQueue();
	SocketChannel socketChannel;
	SelectionKey selectionKey;
	Connection connection;
	ConnectorImpl connector;

	Client(ConnectorImpl connector, Connection connection) {
		this.connector = connector;
		this.connection = connection;
		connection.setHandler(new ConnectionHandler() {
			@Override
			public void send(byte[] b, int off, int len) {
				write(b, off, len);
			}

			@Override
			public void setBufferSize(int bufferSize) {
				boolean blocked = Client.this.bufferSize == 0;
				boolean toBlock = bufferSize <= 0;
				Client.this.bufferSize = Math.max(0,
						Math.min(bufferSize, Connection.MAX_BUFFER_SIZE));
				if (status != STATUS_CLOSED && (blocked ^ toBlock)) {
					interestOps();
				}
			}

			@Override
			public void disconnect() {
				if (status == STATUS_IDLE) {
					finishClose();
				} else if (status == STATUS_BUSY) {
					status = STATUS_DISCONNECTING;
				}
			}
		});
	}

	void add(Selector selector, int ops) {
		try {
			selectionKey = socketChannel.register(selector, ops, this);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * registers a {@link Client} and connects to a remote address
	 *
	 * @see ConnectorImpl#connect(Connection, String, int)
	 */
	void connect(Selector selector, InetSocketAddress socketAddress) {
		resolving = false;
		try {
			socketChannel = SocketChannel.open();
			socketChannel.configureBlocking(false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try {
			socketChannel.connect(socketAddress);
			add(selector, SelectionKey.OP_CONNECT);
		} catch (IOException e) {
			// May throw "Network is unreachable"
			startClose();
		}
	}

	void interestOps() {
		// setBufferSize may be called before resolve
		if (!resolving) {
			selectionKey.interestOps((bufferSize == 0 ? 0 : SelectionKey.OP_READ) |
					(status == STATUS_IDLE ? 0 : SelectionKey.OP_WRITE));
		}
	}

	void write() throws IOException {
		int len = queue.length();
		int originalLen = len;
		while (len > 0) {
			int bytesWritten = socketChannel.write(ByteBuffer.wrap(queue.array(),
					queue.offset(), queue.length()));
			if (bytesWritten == 0) {
				if (len < originalLen) {
					connection.onQueue(len);
				}
				interestOps();
				return;
			}
			len = queue.remove(bytesWritten).length();
		}
		if (len < originalLen) {
			connection.onQueue(len);
		}
		if (status == STATUS_DISCONNECTING) {
			finishClose();
		} else {
			status = STATUS_IDLE;
			interestOps();
		}
	}

	void write(byte[] b, int off, int len) {
		if (status != STATUS_IDLE) {
			queue.add(b, off, len);
			connection.onQueue(queue.length());
			return;
		}
		int bytesWritten;
		try {
			bytesWritten = socketChannel.write(ByteBuffer.wrap(b, off, len));
		} catch (IOException e) {
			startClose();
			return;
		}
		if (len > bytesWritten) {
			queue.add(b, off + bytesWritten, len - bytesWritten);
			connection.onQueue(queue.length());
			status = STATUS_BUSY;
			interestOps();
		}
	}

	void startConnect() {
		status = STATUS_BUSY;
	}

	void finishConnect() {
		InetSocketAddress local = ((InetSocketAddress) socketChannel.
				socket().getLocalSocketAddress());
		InetSocketAddress remote = ((InetSocketAddress) socketChannel.
				socket().getRemoteSocketAddress());
		connection.onConnect(new ConnectionSession(local, remote));
	}

	void startClose() {
		if (status != STATUS_CLOSED) {
			finishClose();
			// Call "close()" before "onDisconnect()"
			// to avoid recursive "disconnect()".
			connection.onDisconnect();
		}
	}

	void finishClose() {
		if (!resolving) {
			selectionKey.cancel();
			try {
				socketChannel.close();
			} catch (IOException e) {/**/}
		}
		status = STATUS_CLOSED;
	}

	boolean isBusy() {
		return status >= STATUS_IDLE;
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
	ConnectorImpl connector;

	/**
	 * Opens a listening port and binds to a given address.
	 * @param addr - The IP address to bind and the port to listen.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	Server(ConnectorImpl connector, ServerConnection serverConnection,
			InetSocketAddress addr) throws IOException {
		this.connector = connector;
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

class Timer implements Comparable<Timer> {
	long uptime;
	long id;

	@Override
	public int compareTo(Timer o) {
		int result = Long.compare(uptime, o.uptime);
		return result == 0 ? Long.compare(id, o.id) : result;
	}

	@Override
	public String toString() {
		return new Date(uptime) + "/" + id;
	}
}

/**
 * The encapsulation of {@link Selector},
 * which makes {@link Client} and {@link Server} working.<p>
 */
public class ConnectorImpl implements Connector, TimerHandler, EventQueue, Executor, AutoCloseable {
	private static Pattern hostName = Pattern.compile("[a-zA-Z]");
	private static long nextId = 0;

	private Selector selector;
	private boolean interrupted = false;
	private byte[] buffer = new byte[Connection.MAX_BUFFER_SIZE];
	private TreeMap<Timer, Runnable> timerMap = new TreeMap<>();
	private ConcurrentLinkedQueue<Runnable> eventQueue = new ConcurrentLinkedQueue<>();
	private ExecutorService executor = Executors.newCachedThreadPool();

	{
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** @throws IOException if no IP address for the <code>host</code> could be found*/
	@Override
	public void connect(Connection connection,
			InetSocketAddress socketAddress) throws IOException {
		Client client = new Client(this, connection);
		client.startConnect();
		if (!socketAddress.isUnresolved()) {
			client.connect(selector, socketAddress);
			return;
		}
		String host = socketAddress.getHostName();
		int port = socketAddress.getPort();
		if (host.indexOf(':') >= 0 || !hostName.matcher(host).find()) {
			// Connect immediately for IPv6 or IPv4 Address 
			client.connect(selector,
					new InetSocketAddress(InetAddress.getByName(host), port));
			return;
		}
		client.resolving = true;
		execute(() -> {
			try {
				// Resolve in Executor then Connect later
				InetAddress addr = InetAddress.getByName(host);
				invokeLater(() -> client.connect(selector,
						new InetSocketAddress(addr, port)));
			} catch (IOException e) {
				invokeLater(connection::onDisconnect);
			}
		});
	}

	@Override
	public Connector.Closeable add(ServerConnection serverConnection,
			InetSocketAddress socketAddress) throws IOException {
		Server server = new Server(this, serverConnection, socketAddress);
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
			Iterator<Map.Entry<Timer, Runnable>> it = timerMap.entrySet().iterator();
			if (it.hasNext()) {
				long timeout = it.next().getKey().uptime - System.currentTimeMillis();
				doEvents(timeout > 0 ? timeout : 0);
			} else {
				doEvents(-1);
			}
		}
	}

	private void invokeQueue() {
		long now = System.currentTimeMillis();
		ArrayList<Runnable> runnables = new ArrayList<>();
		Iterator<Map.Entry<Timer, Runnable>> it = timerMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Timer, Runnable> entry = it.next();
			if (entry.getKey().uptime <= now) {
				runnables.add(entry.getValue());
				it.remove();
			}
		}
		// call run() after iteration since run() may change timerMap
		for (Runnable runnable : runnables) {
			runnable.run();
		}
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
				Client client = new Client(this, server.serverConnection.get());
				client.socketChannel = socketChannel;
				client.add(selector, SelectionKey.OP_READ);
				client.finishConnect();
				continue;
			}
			Client client = (Client) key.attachment();
			try {
				if (key.isReadable()) {
					int bytesRead = client.socketChannel.
							read(ByteBuffer.wrap(buffer, 0, client.bufferSize));
					if (bytesRead > 0) {
						client.connection.onRecv(buffer, 0, bytesRead);
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
				if (key.isWritable()) {
					client.write();
				} else if (key.isConnectable() && client.socketChannel.finishConnect()) {
					client.finishConnect();
					// "onConnect()" might call "disconnect()"
					if (client.isBusy()) {
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

	@Override
	public TimerHandler.Closeable postAtTime(Runnable runnable, long uptime) {
		Timer timer = new Timer();
		timer.uptime = uptime;
		timer.id = nextId;
		nextId ++;
		timerMap.put(timer, runnable);
		return () -> timerMap.remove(timer);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		eventQueue.offer(runnable);
		selector.wakeup();
	}

	@Override
	public void execute(Runnable runnable) {
		executor.execute(runnable);
	}

	/**
	 * Interrupts {@link #doEvents()} or {@link #doEvents(long)}.<p>
	 * <b>Can be called outside main thread.</b>
	 */
	public void interrupt() {
		invokeLater(() -> interrupted = true);
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
		executor.shutdown();
		for (SelectionKey key : selector.keys()) {
			Object o = key.attachment();
			if (o instanceof Server) {
				((Server) o).close();
			} else {
				((Client) o).finishClose();
			}
		}
		try {
			selector.close();
		} catch (IOException e) {/**/}
	}
}