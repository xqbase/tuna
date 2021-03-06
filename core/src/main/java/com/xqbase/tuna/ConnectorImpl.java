package com.xqbase.tuna;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.xqbase.tuna.util.ByteArrayQueue;

abstract class Attachment {
	SelectionKey selectionKey;

	abstract void closeChannel();

	void finishClose() {
		selectionKey.cancel();
		closeChannel();
	}
}

/**
 * The encapsulation of a {@link SocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Socket.
 */
class Client extends Attachment {
	private static final int STATUS_CLOSED = 0;
	private static final int STATUS_IDLE = 1;
	private static final int STATUS_BUSY = 2;
	private static final int STATUS_DISCONNECTING = 3;

	int bufferSize = Connection.MAX_BUFFER_SIZE;
	int status = STATUS_IDLE;
	boolean resolving = false;
	ByteArrayQueue queue = new ByteArrayQueue();
	Connection connection;
	SocketChannel socketChannel;

	Client(Connection connection) {
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
				if ((blocked ^ toBlock) && !resolving && isOpen() &&
						(selectionKey.interestOps() & SelectionKey.OP_CONNECT) == 0) {
					// may be called before resolve
					interestOps();
				}
			}

			@Override
			public void disconnect() {
				if (status == STATUS_IDLE) {
					// must be resolved
					finishClose();
				} else if (status == STATUS_BUSY) {
					status = STATUS_DISCONNECTING;
				}
			}

			@Override
			public void disconnectNow() {
				if (isOpen()) {
					finishClose();
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
	 * @throws IOException may throw "Network is unreachable" or "Protocol family unavailable",
	 *		   and then socketChannel will be closed, and selectionKey will not be created
	 */
	void connect(Selector selector, InetSocketAddress socketAddress) throws IOException {
		resolving = false;
		try {
			socketChannel = SocketChannel.open();
			socketChannel.configureBlocking(false);
			socketChannel.socket().setTcpNoDelay(true);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try {
			socketChannel.connect(socketAddress);
			add(selector, SelectionKey.OP_CONNECT);
		} catch (IOException e) {
			// May throw "Network is unreachable" or "Protocol family unavailable",
			// and then socketChannel will be closed, and selectionKey will not be created
			closeChannel();
			throw e;
		}
	}

	void interestOps() {
		selectionKey.interestOps((bufferSize == 0 ? 0 : SelectionKey.OP_READ) |
				(status == STATUS_IDLE ? 0 : SelectionKey.OP_WRITE));
	}

	void write() throws IOException {
		int len = queue.length();
		int fromLen = len;
		while (len > 0) {
			int bytesWritten = socketChannel.write(ByteBuffer.wrap(queue.array(),
					queue.offset(), len));
			if (bytesWritten == 0) {
				unblock(len, fromLen);
				return;
			}
			len = queue.remove(bytesWritten).length();
		}
		unblock(len, fromLen);
		if (status == STATUS_DISCONNECTING) {
			finishClose();
		} else {
			status = STATUS_IDLE;
			interestOps();
		}
	}

	void write(byte[] b, int off, int len) {
		if (status != STATUS_IDLE) {
			int fromLen = queue.length();
			block(queue.add(b, off, len).length(), fromLen);
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
			int fromLen = queue.length();
			block(queue.add(b, off + bytesWritten, len - bytesWritten).length(), fromLen);
			status = STATUS_BUSY;
			interestOps();
		}
	}

	void startConnect() {
		status = STATUS_BUSY;
	}

	void finishConnect() {
		Socket socket = socketChannel.socket();
		InetSocketAddress local = (InetSocketAddress) socket.getLocalSocketAddress();
		InetSocketAddress remote = (InetSocketAddress) socket.getRemoteSocketAddress();
		connection.onConnect(new ConnectionSession(local, remote));
	}

	void startClose() {
		if (isOpen()) {
			finishClose();
			// Call "close()" before "onDisconnect()"
			// to avoid recursive "disconnect()".
			connection.onDisconnect();
		}
	}

	@Override
	void closeChannel() {
		try {
			socketChannel.close();
		} catch (IOException e) {/**/}
		status = STATUS_CLOSED;
	}

	boolean isOpen() {
		return status != STATUS_CLOSED;
	}

	private boolean blocking = false;

	private void block(int len, int fromLen) {
		if (fromLen > 0) {
			blocking = true;
			connection.onQueue(len);
		}
	}

	private void unblock(int len, int fromLen) {
		if (len == fromLen) {
			return;
		}
		boolean fromBlocking = blocking;
		if (len == 0) {
			blocking = false;
		}
		if (fromBlocking) {
			connection.onQueue(len);
		}
	}
}

/**
 * The encapsulation of a {@link ServerSocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Server Socket
 */
class Server extends Attachment {
	ServerConnection serverConnection;
	ServerSocketChannel serverSocketChannel;

	/**
	 * Opens a listening port and binds to a given address.
	 * @param addr - The IP address to bind and the port to listen.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	Server(ServerConnection serverConnection,
			InetSocketAddress addr) throws IOException {
		this.serverConnection = serverConnection;
		bind(addr);
	}

	void bind(InetSocketAddress addr) throws IOException {
		try {
			serverSocketChannel = ServerSocketChannel.open();
			serverSocketChannel.configureBlocking(false);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		try {
			serverSocketChannel.socket().bind(addr);
		} catch (IOException e) {
			closeChannel();
			throw e;
		}
	}

	@Override
	void closeChannel() {
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

class Registrable {
	private SelectableChannel channel;
	private int interestOps;
	private Attachment att;

	/** @throws IOException if ServerSocket fails to Bind again */
	Registrable(SelectionKey key) throws IOException {
		channel = key.channel();
		interestOps = key.interestOps();
		att = (Attachment) key.attachment();
		key.cancel(); // Need to cancel ?
		if (!(att instanceof Server)) {
			return;
		}
		// Rebuild ServerSocketChannel
		Server server = (Server) att;
		InetSocketAddress addr = (InetSocketAddress) server.serverSocketChannel.
				socket().getLocalSocketAddress();
		server.closeChannel();
		server.bind(addr);
		channel = server.serverSocketChannel;
	}

	void register(Selector selector) throws IOException {
		if ((interestOps & SelectionKey.OP_CONNECT) != 0) {
			((Client) att).startClose();
			// Log.w("Removed a Connection-Pending Channel, interestOps = " + interestOps);
			return;
		}
		// Client may be closed by closing of Connection-Pending Channel
		if (att instanceof Client && !((Client) att).isOpen()) {
			return;
		}
		att.selectionKey = channel.register(selector, interestOps, att);
	}
}

/**
 * The encapsulation of {@link Selector},
 * which makes {@link Client} and {@link Server} working.<p>
 */
public class ConnectorImpl implements Connector, TimerHandler, EventQueue, Executor, AutoCloseable {
	private static Pattern hostName = Pattern.compile("[a-zA-Z]");
	private static long nextId = 0;

	private volatile Selector selector;
	private boolean interrupted = false;
	private byte[] buffer = new byte[Connection.MAX_BUFFER_SIZE];
	private Map<Timer, Runnable> timerMap = new TreeMap<>();
	private Queue<Runnable> eventQueue = new ConcurrentLinkedQueue<>();
	private ExecutorService executor = Executors.newCachedThreadPool();

	{
		try {
			selector = Selector.open();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/** @throws IOException if no IP address for the <code>host</code> could be found */
	@Override
	public void connect(Connection connection,
			InetSocketAddress socketAddress) throws IOException {
		Client client = new Client(connection);
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
				invokeLater(() -> {
					try {
						client.connect(selector, new InetSocketAddress(addr, port));
					} catch (IOException e) {
						// Call "onDisconnect()" when Connecting Failure
						connection.onDisconnect();
					}
				});
			} catch (IOException e) {
				// Call "onDisconnect()" when Resolving Failure
				invokeLater(connection::onDisconnect);
			}
		});
	}

	@Override
	public Connector.Closeable add(ServerConnection serverConnection,
			InetSocketAddress socketAddress) throws IOException {
		Server server = new Server(serverConnection, socketAddress);
		try {
			server.selectionKey = server.serverSocketChannel.
					register(selector, SelectionKey.OP_ACCEPT, server);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return () -> {
			if (server.selectionKey.isValid()) {
				server.finishClose();
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
		List<Runnable> runnables = new ArrayList<>();
		Iterator<Map.Entry<Timer, Runnable>> it = timerMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Timer, Runnable> entry = it.next();
			if (entry.getKey().uptime > now) {
				break;
			}
			runnables.add(entry.getValue());
			it.remove();
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

	private static boolean shortTime(long millis) {
		return millis >= 0 && millis < 16;
	}

	private int epollCount = 0;

	private void checkEpoll(long timeout, long t, int keySize) {
		if (keySize > 0 || shortTime(timeout) || 
				!shortTime(System.currentTimeMillis() - t) ||
				!eventQueue.isEmpty() ||
				Thread.currentThread().isInterrupted()) {
			epollCount = 0;
			return;
		}
		Set<SelectionKey> keys = selector.keys();
		// "select()" may exit immediately due to a Broken Connection ?
		/* for (SelectionKey key : keys) {
			Object att = key.attachment();
			if (!(att instanceof Client)) {
				continue;
			}
			Client client = (Client) att;
			if (!client.socketChannel.isConnected() &&
					!client.socketChannel.isConnectionPending()) {
				Log.w("Abort Registering New Selector, timeout = " + timeout +
						", t0 = " + Time.toString(t, true) + ", t1 = " +
						Time.toString(System.currentTimeMillis(), true));
				client.startClose();
				epollCount = 0;
				return;
			}
		} */
		// E-Poll Spin Detected
		epollCount ++;
		if (epollCount < 256) {
			return;
		}
		epollCount = 0;
		/* Log.w("Epoll Spin Detected, timeout = " + timeout +
				", t0 = " + Time.toString(t, true) + ", t1 = " +
				Time.toString(System.currentTimeMillis(), true) +
				", keys = " + keys.size()); */
		// Log.w("Begin Registering New Selector " + selector + " ...");
		List<Registrable> regs = new ArrayList<>();
		for (SelectionKey key : keys) {
			if (!key.isValid()) {
				// Log.w("Invaid SelectionKey Detected");
				continue;
			}
			try {
				regs.add(new Registrable(key));
			} catch (IOException e) {
				// Ignored if ServerSocket fails to Bind again
			}
		}
		try {
			selector.close();
		} catch (IOException e) {/**/}
		try {
			selector = Selector.open();
			for (Registrable reg : regs) {
				reg.register(selector);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		// Log.w("End Registering New Selector " + selector);
	}

	/**
	 * Consumes all events raised by registered Clients and Servers,
	 * including network events (accept/connect/read/write) and user-defined events.<p>
	 *
	 * @param timeout Block for up to timeout milliseconds, or -1 to block indefinitely,
	 *			or 0 without blocking.
	 * @return <b>true</b> if NETWORK events consumed;<br>
	 *			<b>false</b> if no NETWORK events raised,
	 *			whether or not user-defined events raised.<br>
	 */
	public boolean doEvents(long timeout) {
		long t = System.currentTimeMillis();
		int keySize;
		try {
			keySize = timeout == 0 ? selector.selectNow() :
					timeout < 0 ? selector.select() : selector.select(timeout);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		checkEpoll(timeout, t, keySize);
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
					socketChannel.socket().setTcpNoDelay(true);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				Client client = new Client(server.serverConnection.get());
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
						// Disconnected, so skip onQueue and onConnect
						continue;
					}
				}
				if (key.isWritable()) {
					client.write();
				} else if (key.isConnectable() && client.socketChannel.finishConnect()) {
					client.finishConnect();
					// "onConnect()" might call "disconnect()"
					if (client.isOpen()) {
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
		try {
			selector.wakeup();
		} catch (ClosedSelectorException e) {/**/}
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
			((Attachment) key.attachment()).finishClose();
		}
		try {
			selector.close();
		} catch (IOException e) {/**/}
	}
}