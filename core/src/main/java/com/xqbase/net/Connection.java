package com.xqbase.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;

import com.xqbase.util.ByteArrayQueue;

/**
 * The encapsulation of a {@link SocketChannel} and its {@link SelectionKey},
 * which corresponds to a TCP Socket.
 */
public class Connection implements Handler {
	Listener listener;

	Connection(Listener listener) {
		this.listener = listener;
		listener.setHandler(this);
	}

	Filter netFilter = new Filter() {
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
	};
	private Filter appFilter = new Filter() {
		@Override
		public void onRecv(byte[] b, int off, int len) {
			listener.onRecv(b, off, len);
		}

		@Override
		public void onSend(boolean queued) {
			listener.onSend(queued);
		}

		@Override
		public void onConnect() {
			listener.onConnect();
		}

		@Override
		public void onDisconnect() {
			listener.onDisconnect();
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
		filter.handler = this;
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
	public void appendFilters(List<FilterFactory> filterFactories) {
		for (FilterFactory filterFactory : filterFactories) {
			appendFilter(filterFactory.createFilter());
		}
	}

	private static final int STATUS_IDLE = 0;
	private static final int STATUS_BUSY = 1;
	private static final int STATUS_DISCONNECTING = 2;
	private static final int STATUS_CLOSED = 3;

	private InetSocketAddress local = new InetSocketAddress(0),
			remote = new InetSocketAddress(0);
	private ByteArrayQueue queue = new ByteArrayQueue();
	private boolean blocked = false;

	int status = STATUS_IDLE;
	SocketChannel socketChannel;
	SelectionKey selectionKey;
	Connector connector;

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
			netFilter.onSend(false);
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
				netFilter.onSend(true);
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
			netFilter.onSend(true);
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
		netFilter.onConnect();
	}

	void startClose() {
		if (status == STATUS_CLOSED) {
			return;
		}
		finishClose();
		connector.passiveDisconnectCount ++;
		// Call "close()" before "onDisconnect()"
		// to avoid recursive "disconnect()".
		netFilter.onDisconnect();
	}

	void finishClose() {
		connector.totalQueueSize -= queue.length();
		status = STATUS_CLOSED;
		selectionKey.cancel();
		try {
			socketChannel.close();
		} catch (IOException e) {/**/}
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
	public void invokeLater(Runnable runnable) {
		connector.invokeLater(runnable);
	}

	@Override
	public void blockRecv(boolean blocked_) {
		blocked = blocked_;
		if (status != STATUS_CLOSED) {
			interestOps();
		}
	}

	@Override
	public void disconnect() {
		appFilter.disconnect();
	}

	/** @return <code>true</code> if the connection is not closed. */
	public boolean isOpen() {
		return status != STATUS_CLOSED;
	}

	/** @return <code>true</code> if the connection is not idle. */
	public boolean isBusy() {
		return status != STATUS_IDLE;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		appFilter.send(b, off, len);
	}

	long bytesRecv = 0;
	private long bytesSent = 0;

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

	/** Only for debug. */
	@Override
	public String toString() {
		return String.format("%s<->%s: queueSize=%s, bytesRecv=%s, " +
				"bytesSent=%s, status=%s, interestOps=%s%s", local, remote,
				"" + queue.length(), "" + bytesRecv, "" + bytesSent, "" + status,
				"" + (selectionKey.isValid() ? selectionKey.interestOps() : 0),
				blocked ? ", blocked" : "");
	}
}