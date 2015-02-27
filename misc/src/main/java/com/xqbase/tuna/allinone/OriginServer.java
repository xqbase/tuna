package com.xqbase.tuna.allinone;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class VirtualHandler implements ConnectionHandler {
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
			localAddr = remoteAddr = InetAddress.getLoopbackAddress().getHostAddress();
		}
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] head = new AllInOnePacket(connId,
				AllInOnePacket.ORIGIN_DATA, 0, len).getHead();
		edge.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void disconnect() {
		edge.connMap.remove(Integer.valueOf(connId));
		edge.handler.send(new AllInOnePacket(connId,
				AllInOnePacket.ORIGIN_DISCONNECT, 0, 0).getHead());
	}

	@Override
	public void setBufferSize(int bufferSize) {
		// Nothing to do
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
		int connId = packet.connId;
		int command = packet.command;
		if (command == AllInOnePacket.EDGE_PING) {
			handler.send(new AllInOnePacket(0, AllInOnePacket.ORIGIN_PONG, 0, 0).getHead());
		} else if (command == AllInOnePacket.EDGE_CONNECT) {
			if (connMap.containsKey(Integer.valueOf(connId))) {
				// throw new PacketException("#" + connId + " Already Exists");
				disconnect();
				return;
			}
			Connection connection = origin.server.get();
			VirtualHandler virtualHandler = new VirtualHandler(this,
					connId, Bytes.sub(b, off + 16, len - 16));
			connection.setHandler(virtualHandler);
			connMap.put(Integer.valueOf(connId), connection);
			connection.onConnect();
		} else {
			Connection connection = connMap.get(Integer.valueOf(connId));
			if (connection == null) {
				return;
			}
			if (command == AllInOnePacket.EDGE_DATA) {
				if (16 + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					disconnect();
					return;
				}
				connection.onRecv(b, off + 16, packet.size);
			} else { // AllInOnePacket.EDGE_DISCONNECT
				connMap.remove(Integer.valueOf(connId));
				handler.send(new AllInOnePacket(connId,
						AllInOnePacket.ORIGIN_CLOSE, 0, 0).getHead());
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