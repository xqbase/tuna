package com.xqbase.tuna.mux;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class DirectVirtualHandler implements VirtualHandler {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private EdgeConnection edge;
	private int cid;

	DirectVirtualHandler(EdgeConnection edge, int cid) {
		this.edge = edge;
		this.cid = cid;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		MuxPacket.send(edge.handler, bb, MuxPacket.HANDLER_SEND, cid);
	}

	@Override
	public void setBufferSize(int bufferSize) {
		byte[] b = new byte[HEAD_SIZE + 2];
		Bytes.setShort(bufferSize, b, HEAD_SIZE);
		MuxPacket.send(edge.handler, b, MuxPacket.HANDLER_BUFFER, cid);
	}

	@Override
	public void disconnect() {
		edge.connectionMap.remove(Integer.valueOf(cid));
		MuxPacket.send(edge.handler, MuxPacket.HANDLER_DISCONNECT, cid);
	}

	@Override
	public int getConnectionID() {
		return cid;
	}

	@Override
	public ConnectionHandler getMuxHandler() {
		return edge.handler;
	}
}

class EdgeConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final Connection[] EMPTY_CONNECTIONS = new Connection[0];

	HashMap<Integer, Connection> connectionMap = new HashMap<>();
	long accessed = System.currentTimeMillis();
	ConnectionHandler handler;

	private boolean[] queued = {false};
	private OriginServer origin;
	private boolean authed;

	EdgeConnection(OriginServer origin) {
		this.origin = origin;
		authed = origin.context.test(null);
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		origin.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		origin.timeoutSet.add(this);
		MuxPacket packet = new MuxPacket(b, off);
		int cid = packet.cid;
		switch (packet.cmd) {
		case MuxPacket.CLIENT_PING:
			MuxPacket.send(handler, MuxPacket.SERVER_PONG, 0);
			break;
		case MuxPacket.CLIENT_AUTH:
			authed = origin.context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			MuxPacket.send(handler, authed ? MuxPacket.SERVER_AUTH_OK :
					MuxPacket.SERVER_AUTH_ERROR, 0);
			return;
		case MuxPacket.CONNECTION_CONNECT:
			if (!authed) {
				MuxPacket.send(handler, MuxPacket.SERVER_AUTH_NEED, 0);
				MuxPacket.send(handler, MuxPacket.HANDLER_DISCONNECT, cid);
				return;
			}
			if (connectionMap.containsKey(Integer.valueOf(cid))) {
				// throw new PacketException("#" + cid + " Already Exists");
				disconnect();
				return;
			}
			Connection connection = origin.server.get();
			String localAddr, remoteAddr;
			int localPort, remotePort;
			if (packet.size == 12 || packet.size == 36) {
				int addrLen = packet.size / 2 - 2;
				try {
					localAddr = InetAddress.getByAddress(Bytes.
							sub(b, off + HEAD_SIZE, addrLen)).getHostAddress();
					remoteAddr = InetAddress.getByAddress(Bytes.
							sub(b, off + HEAD_SIZE + addrLen + 2, addrLen)).getHostAddress();
				} catch (IOException e) {
					localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				}
				localPort = Bytes.toShort(Bytes.
						sub(b, off + HEAD_SIZE + addrLen, 2)) & 0xFFFF;
				remotePort = Bytes.toShort(Bytes.
						sub(b, off + HEAD_SIZE + addrLen * 2 + 2, 2)) & 0xFFFF;
			} else {
				localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				localPort = remotePort = 0;
			}
			DirectVirtualHandler virtualHandler = new DirectVirtualHandler(this, cid);
			connection.setHandler(virtualHandler);
			connectionMap.put(Integer.valueOf(cid), connection);
			connection.onConnect(localAddr, localPort, remoteAddr, remotePort);
			break;
		default:
			connection = connectionMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case MuxPacket.CONNECTION_RECV:
				if (packet.size > 0) {
					connection.onRecv(b, off + HEAD_SIZE, packet.size);
				}
				break;
			case MuxPacket.CONNECTION_QUEUE:
				if (packet.size >= 4) {
					connection.onQueue(Bytes.toInt(b, off + HEAD_SIZE));
				}
				break;
			case MuxPacket.CONNECTION_DISCONNECT:
				connectionMap.remove(Integer.valueOf(cid));
				MuxPacket.send(handler, MuxPacket.HANDLER_CLOSE, cid);
				connection.onDisconnect();
			}
		}
	}

	@Override
	public void onQueue(int size) {
		if (!origin.context.isQueueChanged(size, queued)) {
			return;
		}
		// Tell all virtual connections that edge is congested or smooth 
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connecton.onQueue()" might change "connectionMap"
			connection.onQueue(size);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		if (!authed) {
			MuxPacket.send(handler, MuxPacket.SERVER_AUTH_NEED, 0);
		}
	}

	@Override
	public void onDisconnect() {
		origin.timeoutSet.remove(this);
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connecton.onDisconnect()" might change "connectionMap"
			connection.onDisconnect();
		}
	}

	void disconnect() {
		handler.disconnect();
		onDisconnect();
	}
}

/**
 * An origin server can manage a large number of virtual {@link Connection}s
 * via several {@link EdgeServer}s.
 * @see EdgeServer
 */
public class OriginServer implements ServerConnection, AutoCloseable {
	LinkedHashSet<EdgeConnection> timeoutSet = new LinkedHashSet<>();
	ServerConnection server;
	MuxContext context;

	private TimerHandler.Closeable closeable;

	public OriginServer(ServerConnection server, MuxContext context) {
		this.server = server;
		this.context = context;
		closeable = context.scheduleDelayed(() -> {
			long now = System.currentTimeMillis();
			Iterator<EdgeConnection> i = timeoutSet.iterator();
			EdgeConnection edge;
			while (i.hasNext() && now > (edge = i.next()).accessed + 60000) {
				i.remove();
				edge.disconnect();
			}
		}, 1000, 1000);
	}

	@Override
	public Connection get() {
		EdgeConnection edgeConnection = new EdgeConnection(this);
		timeoutSet.add(edgeConnection);
		return edgeConnection.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	@Override
	public void close() {
		closeable.close();
	}
}