package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import com.xqbase.util.ByteArrayQueue;

/**
 * The encapsulation of a {@link SocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Socket.
 */
public class Connection {
	/**
	 * An event which can be raised by {@link #dispatchNow(Event)}
	 * or {@link #dispatchLater(Event)}.
	 */
	public class Event {
		/** Reserved event type which indicates an active disconnecting */
		public static final int DISCONNECT = 0;
		/** Reserved event type which indicates a queued sending */
		public static final int QUEUED = 1;
		/** Reserved event type which indicates that sending is no longer queued */
		public static final int EMPTY = 2;

		private int type;

		/**
		 * Creates an Event with a given type.<p>
		 * A zero type means an active disconnecting, which will successively
		 * be consumed by {@link #onEvent(Event)} and
		 * {@link Connection#onDisconnect()}.<p>
		 * A negative type means a passive disconnecting or a socket error,
		 * which will be consumed by {@link Connection#onDisconnect()} but
		 * will NOT be consumed by {@link #onEvent(Event)}.
		 */
		protected Event(int type) {
			this.type = type;
		}

		/** @return The type of the event. */
		public int getType() {
			return type;
		}
	}

	/**
	 * Consumes received data in the application end of the connection.
	 *
	 * @param b
	 * @param off
	 * @param len
	 */
	protected void onRecv(byte[] b, int off, int len) {/**/}
	/**
	 * Consumes events in the APPLICATION end of the connection.
	 *
	 * @param event
	 */
	protected void onEvent(Event event) {/**/}
	/** Consumes connecting events in the APPLICATION end of the connection. */
	protected void onConnect() {/**/}
	/** Consumes disconnecting events in the APPLICATION end of the connection. */
	protected void onDisconnect() {/**/}

	Filter netFilter = new Filter() {
		@Override
		protected void send(byte[] b, int off, int len) {
			write(b, off, len);
		}
	};
	private Filter appFilter = new Filter() {
		@Override
		protected void onRecv(byte[] b, int off, int len) {
			Connection.this.onRecv(b, off, len);
		}

		@Override
		protected void onEvent(Event event) {
			Connection.this.onEvent(event);
		}

		@Override
		protected void onConnect() {
			Connection.this.onConnect();
		}

		@Override
		protected void onDisconnect() {
			Connection.this.onDisconnect();
		}
	};
	private Filter lastFilter = appFilter;

	{
		netFilter.appFilter = appFilter;
		appFilter.netFilter = netFilter;
	}

	/**
	 * Adds a {@link Filter} into the network end of the filter chain.
	 *
	 * @see Connector#getFilterFactories()
	 * @see ServerConnection#getFilterFactories()
	 */
	public void appendFilter(Filter filter) {
		filter.connection = this;
		// lastFilter <-> filter
		lastFilter.netFilter = filter;
		filter.appFilter = lastFilter;
		// filter <-> netFilter
		filter.netFilter = netFilter;
		netFilter.appFilter = filter;
		lastFilter = filter;
	}

	/**
	 * Adds {@link Filter}s into the network end of the filter chain.
	 * These {@link Filter}s are created by a list (or other collections) of {@link FilterFactory}s,
	 * in a definite order (according to the iteration), from the application side to the network side.
	 *
	 * @param filterFactories - A collection of <b>FilterFactory</b>s.
	 * @see Connector#getFilterFactories()
	 * @see ServerConnection#getFilterFactories()
	 */
	public void appendFilters(Iterable<FilterFactory> filterFactories) {
		for (FilterFactory filterFactory : filterFactories) {
			appendFilter(filterFactory.createFilter());
		}
	}

	private static final int STATUS_IDLE = 0;
	private static final int STATUS_BUSY = 1;
	private static final int STATUS_DISCONNECTING = 2;
	private static final int STATUS_CLOSED = 3;

	private InetSocketAddress local, remote;
	private int status = STATUS_IDLE;
	private ByteArrayQueue queue = new ByteArrayQueue();

	SocketChannel socketChannel;
	SelectionKey selectionKey;
	Connector connector;

	void write() throws IOException {
		while (queue.length() > 0) {
			int bytesWritten = 	socketChannel.write(ByteBuffer.wrap(queue.array(),
					queue.offset(), queue.length()));
			if (bytesWritten == 0) {
				return;
			}
			queue.remove(bytesWritten);
		}
		if (status == STATUS_DISCONNECTING) {
			dispatchNow(Event.DISCONNECT);
		} else {
			selectionKey.interestOps(SelectionKey.OP_READ);
			status = STATUS_IDLE;
			dispatchNow(Event.EMPTY);
		}
	}

	void write(byte[] b, int off, int len) {
		if (status != STATUS_IDLE) {
			queue.add(b, off, len);
			return;
		}
		int bytesWritten;
		try {
			bytesWritten = socketChannel.write(ByteBuffer.wrap(b, off, len));
		} catch (IOException e) {
			disconnect();
			return;
		}
		if (bytesWritten < len) {
			queue.add(b, off + bytesWritten, len - bytesWritten);
			selectionKey.interestOps(SelectionKey.OP_WRITE);
			status = STATUS_BUSY;
			dispatchNow(Event.QUEUED);
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
		netFilter.onConnect();
		// "onConnect()" might call "disconnect()"
		if (status == STATUS_IDLE || status == STATUS_CLOSED) {
			return;
		}
		if (queue.length() == 0) {
			if (status == STATUS_DISCONNECTING) {
				dispatchNow(Event.DISCONNECT);
			} else {
				selectionKey.interestOps(SelectionKey.OP_READ);
				status = STATUS_IDLE;
			}
		} else {
			selectionKey.interestOps(SelectionKey.OP_WRITE);
			dispatchNow(Event.QUEUED);
		}
	}

	/** @return Local IP address of the Connection. */
	public String getLocalAddr() {
		return local.getAddress().getHostAddress();
	}

	/** @return Local port of the Connection. */
	public int getLocalPort() {
		return local.getPort();
	}

	/** @return Remote IP address of the Connection. */
	public String getRemoteAddr() {
		return remote.getAddress().getHostAddress();
	}

	/** @return Remote port of the Connection. */
	public int getRemotePort() {
		return remote.getPort();
	}

	/** Raises an event with a given type immediately. */
	public void dispatchNow(int type) {
		dispatchNow(new Event(type));
	}

	private Runnable getRunnable(Event event) {
		return () -> {
			if (!isOpen()) {
				return;
			}
			if (event.getType() >= 0) {
				netFilter.onEvent(event);
			}
			if (event.getType() <= 0) {
				close();
				// Call "close()" before "onDisconnect()"
				// to avoid recursive "disconnect()".
				netFilter.onDisconnect();
			}
		};
	}

	/** Raises an event with a given event immediately. */
	public void dispatchNow(Event event) {
		getRunnable(event).run();
	}

	/** Raises an event with a given type later in another thread. */
	public void dispatchLater(int type) {
		dispatchLater(new Event(type));
	}

	/** Raises an event with a given event later in another thread. */
	public void dispatchLater(Event event) {
		invokeLater(getRunnable(event));
	}

	/** Invokes a {@link Runnable} in main thread */
	public void invokeLater(Runnable runnable) {
		connector.invokeLater(runnable);
	}

	/**
	 * Closes the connection actively.<p>
	 *
	 * If <code>send()</code> is called before <code>disconnect()</code>,
	 * the connection will not be closed until all queued data sent out. 
	 */
	public void disconnect() {
		if (status == STATUS_IDLE) {
			dispatchNow(Event.DISCONNECT);
		} else if (status == STATUS_BUSY) {
			status = STATUS_DISCONNECTING;
		}
	}

	/** @return <code>true</code> if the connection is connecting or sending data. */
	public boolean isBusy() {
		return status != STATUS_IDLE;
	}

	/** @return <code>true</code> if the connection is not closed. */
	public boolean isOpen() {
		return status != STATUS_CLOSED;
	}

	/**
	 * Sends a sequence of bytes in the application end,
	 * equivalent to <code>send(b, 0, b.length).</code>
	 */ 
	public void send(byte[] b) {
		send(b, 0, b.length);
	}

	/** Sends a sequence of bytes in the application end. */ 
	public void send(byte[] b, int off, int len) {
		appFilter.send(b, off, len);
	}

	void close() {
		status = STATUS_CLOSED;
		selectionKey.cancel();
		try {
			socketChannel.close();
		} catch (IOException e) {/**/}
	}
}