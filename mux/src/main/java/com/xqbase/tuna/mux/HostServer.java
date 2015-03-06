package com.xqbase.tuna.mux;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.portmap.PortMapClient;
import com.xqbase.tuna.util.Bytes;

class PublicConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private HostMuxConnection mux;
	private int cid;

	ConnectionHandler handler;

	PublicConnection(HostMuxConnection mux, int cid) {
		this.mux = mux;
		this.cid = cid;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		MuxPacket.send(mux.handler, bb, MuxPacket.CONNECTION_RECV, cid);
	}

	@Override
	public void onQueue(int size) {
		byte[] bb = new byte[HEAD_SIZE + 4];
		Bytes.setInt(size, bb, HEAD_SIZE);
		MuxPacket.send(mux.handler, bb, MuxPacket.CONNECTION_QUEUE, cid);
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		byte[] localAddrBytes, remoteAddrBytes;
		try {
			localAddrBytes = InetAddress.getByName(localAddr).getAddress();
			remoteAddrBytes = InetAddress.getByName(remoteAddr).getAddress();
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
		byte[] b = new byte[HEAD_SIZE + localAddrBytes.length + remoteAddrBytes.length + 4];
		System.arraycopy(localAddrBytes, 0, b, HEAD_SIZE, localAddrBytes.length);
		Bytes.setShort(localPort, b, HEAD_SIZE + localAddrBytes.length);
		System.arraycopy(remoteAddrBytes, 0, b,
				HEAD_SIZE + localAddrBytes.length + 2, localAddrBytes.length);
		Bytes.setShort(remotePort, b, HEAD_SIZE +
				localAddrBytes.length + 2 + remoteAddrBytes.length);
		MuxPacket.send(mux.handler, b, MuxPacket.CONNECTION_CONNECT, cid);
	}

	@Override
	public void onDisconnect() {
		mux.connectionMap.remove(Integer.valueOf(cid));
		// Do not return connId until CLIENT_CLOSE received
		// map.publicServer.idPool.returnId(connId);
		MuxPacket.send(mux.handler, MuxPacket.CONNECTION_DISCONNECT, cid);
	}
}

class HostMuxConnection implements Connection, ServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final PublicConnection[] EMPTY_CONNECTIONS = new PublicConnection[0];

	HashMap<Integer, PublicConnection> connectionMap = new HashMap<>();
	long accessed = System.currentTimeMillis();
	ConnectionHandler handler;

	private IdPool idPool = new IdPool();
	private Connector.Closeable closeable = null;
	private boolean[] queued = {false};
	private HostServer host;
	private boolean authed;

	HostMuxConnection(HostServer host) {
		this.host = host;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		host.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		host.timeoutSet.add(this);
		MuxPacket packet = new MuxPacket(b, off);
		int cid = packet.cid;
		switch (packet.cmd) {
		case MuxPacket.CLIENT_PING:
			MuxPacket.send(handler, MuxPacket.SERVER_PONG, 0);
			return;
		case MuxPacket.CLIENT_AUTH:
			authed = host.context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			MuxPacket.send(handler, authed ? MuxPacket.SERVER_AUTH_OK :
					MuxPacket.SERVER_AUTH_ERROR, 0);
			return;
		case MuxPacket.CLIENT_LISTEN:
			if (!authed) {
				MuxPacket.send(handler, MuxPacket.SERVER_AUTH_NEED, 0);
				MuxPacket.send(handler, MuxPacket.SERVER_LISTEN_ERROR, cid);
				return;
			}
			if (closeable != null) {
				disconnect();
				return;
			}
			try {
				closeable = host.connector.add(this, cid);
			} catch (IOException e) {
				MuxPacket.send(handler, MuxPacket.SERVER_LISTEN_ERROR, cid);
			}
			return;
		case MuxPacket.HANDLER_MULTICAST:
			// TODO Multicast
			return;
		case MuxPacket.HANDLER_CLOSE:
			idPool.returnId(cid);
			return;
		default:
			PublicConnection connection = connectionMap.get(Integer.valueOf(cid));
			if (closeable == null || connection == null) {
				return;
			}
			switch (packet.cmd) {
			case MuxPacket.HANDLER_SEND:
				if (packet.size > 0) {
					connection.handler.send(b, off + HEAD_SIZE, packet.size);
				}
				return;
			case MuxPacket.HANDLER_BUFFER:
				if (packet.size >= 2) {
					connection.handler.setBufferSize(Bytes.
							toShort(b, off + HEAD_SIZE) & 0xFFFF);
				}
				return;
			case MuxPacket.HANDLER_DISCONNECT:
				connectionMap.remove(Integer.valueOf(cid));
				idPool.returnId(cid);
				connection.handler.disconnect();
				return;
			}
		}
	}

	@Override
	public void onQueue(int size) {
		if (!host.context.isQueueChanged(size, queued)) {
			return;
		}
		int bufferSize = size == 0 ? 0 : Connection.MAX_BUFFER_SIZE;
		// block or unblock all "PublicConnection"s when origin is congested or smooth
		for (PublicConnection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "setBuferSize" might change "connectionMap"
			connection.handler.setBufferSize(bufferSize);
		}
	}

	@Override
	public void onDisconnect() {
		host.timeoutSet.remove(this);
		if (closeable == null) {
			return;
		}
		closeable.close();
		for (PublicConnection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "disconnect()" might change "connectionMap"
			connection.handler.disconnect();
		}
	}

	void disconnect() {
		handler.disconnect();
		onDisconnect();
	}

	@Override
	public Connection get() {
		int cid = idPool.borrowId();
		PublicConnection connection = new PublicConnection(this, cid);
		connectionMap.put(Integer.valueOf(cid), connection);
		return connection;
	}
}

/**
 * A Port Mapping Server, which provides the mapping service for {@link PortMapClient}s.
 * This server will open public ports, which map private ports provided by PortMapClients.
 * @see PortMapClient
 */
public class HostServer implements ServerConnection, AutoCloseable {
	LinkedHashSet<HostMuxConnection> timeoutSet = new LinkedHashSet<>();
	Connector connector;
	MuxContext context;

	private TimerHandler.Closeable closeable;

	/**
	 * Creates a PortMapServer.
	 * @param connector - The {@link Connector} which public connections are registered to.
	 */
	public HostServer(Connector connector, MuxContext context) {
		this.connector = connector;
		this.context = context;
		closeable = context.scheduleDelayed(() -> {
			long now = System.currentTimeMillis();
			Iterator<HostMuxConnection> i = timeoutSet.iterator();
			HostMuxConnection guest;
			while (i.hasNext() && now > (guest = i.next()).accessed + 60000) {
				i.remove();
				guest.disconnect();
			}
		}, 1000, 1000);
	}

	@Override
	public Connection get() {
		HostMuxConnection guest = new HostMuxConnection(this);
		timeoutSet.add(guest);
		return guest.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	@Override
	public void close() {
		closeable.close();
	}
}