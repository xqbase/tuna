package com.xqbase.tuna.allinone;

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

class VirtualHandler implements ConnectionHandler {
	private static final int HEAD_SIZE = AllInOnePacket.HEAD_SIZE;

	private String localAddr, remoteAddr;
	private int localPort, remotePort;

	EdgeConnection edge;
	int connId;

	VirtualHandler(EdgeConnection edge, int connId, byte[] addr) {
		this.edge = edge;
		this.connId = connId;
		if (addr.length == 12 || addr.length == 36) {
			int addrLen = addr.length / 2 - 2;
			try {
				localAddr = InetAddress.getByAddress(Bytes.sub(addr, 0, addrLen)).getHostAddress();
				remoteAddr = InetAddress.getByAddress(Bytes.sub(addr, addrLen + 2, addrLen)).getHostAddress();
			} catch (IOException e) {
				localAddr = remoteAddr = InetAddress.getLoopbackAddress().getHostAddress();
			}
			localPort = Bytes.toShort(Bytes.sub(addr, addrLen, 2)) & 0xFFFF;
			remotePort = Bytes.toShort(Bytes.sub(addr, addrLen * 2 + 2, 2)) & 0xFFFF;
		} else {
			localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
		}
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] head = new AllInOnePacket(len,
				AllInOnePacket.HANDLER_SEND, connId).getHead();
		edge.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void disconnect() {
		edge.connMap.remove(Integer.valueOf(connId));
		edge.handler.send(new AllInOnePacket(0,
				AllInOnePacket.HANDLER_DISCONNECT, connId).getHead());
	}

	@Override
	public void setBufferSize(int bufferSize) {
		byte[] b = new byte[HEAD_SIZE + 2];
		new AllInOnePacket(2, AllInOnePacket.HANDLER_BUFFER, connId).fillHead(b, 0);
		Bytes.setShort(bufferSize, b, HEAD_SIZE);
		edge.handler.send(b);
	}

	@Override
	public String getLocalAddr() {
		return localAddr;
	}

	@Override
	public int getLocalPort() {
		return localPort;
	}

	@Override
	public String getRemoteAddr() {
		return remoteAddr;
	}

	@Override
	public int getRemotePort() {
		return remotePort;
	}

	@Override
	public Closeable postAtTime(Runnable runnable, long uptime) {
		return edge.handler.postAtTime(runnable, uptime);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		edge.handler.invokeLater(runnable);
	}

	@Override
	public void execute(Runnable command) {
		edge.handler.execute(command);
	}
}

class EdgeConnection implements Connection {
	private static final int HEAD_SIZE = AllInOnePacket.HEAD_SIZE;

	HashMap<Integer, Connection> connMap = new HashMap<>();
	OriginServer origin;
	ConnectionHandler handler;
	long accessed = System.currentTimeMillis();

	EdgeConnection(OriginServer origin) {
		this.origin = origin;
		appendFilter(new PacketFilter(AllInOnePacket.getParser()));
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
		AllInOnePacket packet = new AllInOnePacket(b, off, len);
		int cid = packet.cid;
		switch (packet.cmd) {
		case AllInOnePacket.CLIENT_PING:
			handler.send(new AllInOnePacket(0, AllInOnePacket.SERVER_PONG, 0).getHead());
			break;
		case AllInOnePacket.CLIENT_AUTH:
			// TODO Auth
			return;
		case AllInOnePacket.CONNECTION_CONNECT:
			if (connMap.containsKey(Integer.valueOf(cid))) {
				// throw new PacketException("#" + connId + " Already Exists");
				disconnect();
				return;
			}
			Connection connection = origin.server.get();
			VirtualHandler virtualHandler = new VirtualHandler(this,
					cid, Bytes.sub(b, off + HEAD_SIZE, len - HEAD_SIZE));
			connection.setHandler(virtualHandler);
			connMap.put(Integer.valueOf(cid), connection);
			connection.onConnect();
			break;
		default:
			connection = connMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case AllInOnePacket.CONNECTION_RECV:
				if (packet.size > 0) {
					connection.onRecv(b, off + HEAD_SIZE, packet.size);
				}
				break;
			case AllInOnePacket.CONNECTION_QUEUE:
				if (packet.size >= 8) {
					connection.onQueue(Bytes.toInt(b, off + HEAD_SIZE),
							Bytes.toInt(b, off + HEAD_SIZE + 4));
				}
				break;
			case AllInOnePacket.CONNECTION_DISCONNECT:
				connMap.remove(Integer.valueOf(cid));
				handler.send(new AllInOnePacket(0,
						AllInOnePacket.HANDLER_CLOSE, cid).getHead());
				connection.onDisconnect();
			}
		}
	}

	@Override
	public void onDisconnect() {
		origin.timeoutSet.remove(this);
		for (Connection connection : connMap.values().toArray(new Connection[0])) {
			// "conn.onDisconnect()" might change "connMap"
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

	private TimerHandler.Closeable closeable;

	public OriginServer(ServerConnection server, TimerHandler timer) {
		this.server = server;
		closeable = timer.scheduleDelayed(() -> {
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
		return edgeConnection;
	}

	@Override
	public void close() {
		closeable.close();
	}
}