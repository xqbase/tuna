package com.xqbase.tuna.multicast;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class IdPool {
	private int maxId = 0;

	private ArrayDeque<Integer> returned = new ArrayDeque<>();
	private HashSet<Integer> borrowed = new HashSet<>();

	public int borrowId() {
		Integer i = returned.poll();
		if (i == null) {
			i = Integer.valueOf(maxId);
			maxId ++;
		}
		borrowed.add(i);
		return i.intValue();
	}

	public void returnId(int id) {
		Integer i = Integer.valueOf(id);
		if (borrowed.remove(i)) {
			returned.offer(i);
		}
	}
}

class ClientConnection implements Connection {
	private OriginConnection origin;
	private int connId;

	ConnectionHandler handler;

	ClientConnection(OriginConnection origin) {
		this.origin = origin;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] head = new MulticastPacket(connId,
				MulticastPacket.EDGE_DATA, 0, len).getHead();
		origin.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onConnect() {
		connId = origin.idPool.borrowId();
		byte[] localAddrBytes, remoteAddrBytes;
		try {
			localAddrBytes = InetAddress.getByName(handler.getLocalAddr()).getAddress();
			remoteAddrBytes = InetAddress.getByName(handler.getRemoteAddr()).getAddress();
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
		byte[] head = new MulticastPacket(connId, MulticastPacket.EDGE_CONNECT,
				0, localAddrBytes.length + remoteAddrBytes.length + 4).getHead();
		origin.handler.send(Bytes.add(head, localAddrBytes, Bytes.fromShort(handler.getLocalPort()),
				remoteAddrBytes, Bytes.fromShort(handler.getRemotePort())));
		origin.connMap.put(Integer.valueOf(connId), this);
	}

	boolean activeClose = false;

	@Override
	public void onDisconnect() {
		if (activeClose) {
			return;
		}
		origin.connMap.remove(Integer.valueOf(connId));
		// Do not return connId until ORIGIN_CLOSE received
		// origin.idPool.returnId(connId);
		origin.handler.send(new MulticastPacket(connId,
				MulticastPacket.EDGE_DISCONNECT, 0, 0).getHead());
	}
}

class OriginConnection implements Connection {
	private TimerHandler.Closeable closeable = null;

	HashMap<Integer, ClientConnection> connMap = new HashMap<>();
	IdPool idPool = new IdPool();
	TimerHandler timer;
	ConnectionHandler handler;

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MulticastPacket packet = new MulticastPacket(b, off, len);
		int command = packet.command;
		if (command == MulticastPacket.ORIGIN_PONG) {
			return;
		}
		if (command == MulticastPacket.ORIGIN_MULTICAST) {
			int numConns = packet.numConns;
			if (16 + numConns * 4 + packet.size > len) {
				// throw new PacketException("Wrong Packet Size");
				handler.disconnect();
				onDisconnect();
				return;
			}
			for (int i = 0; i < numConns; i ++) {
				int connId = Bytes.toInt(b, off + 16 + i * 4);
				ClientConnection conn = connMap.get(Integer.valueOf(connId));
				if (conn != null) {
					conn.handler.send(b, off + 16 + numConns * 4, packet.size);
				}
			}
		} else {
			int connId = packet.connId;
			if (command == MulticastPacket.ORIGIN_CLOSE) {
				idPool.returnId(connId);
			}
			ClientConnection connection = connMap.get(Integer.valueOf(connId));
			if (connection == null) {
				return;
			}
			if (command == MulticastPacket.ORIGIN_DATA) {
				if (16 + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					handler.disconnect();
					onDisconnect();
					return;
				}
				connection.handler.send(b, off + 16, packet.size);
			} else { // MulticastPacket.ORIGIN_DISCONNECT
				connMap.remove(Integer.valueOf(connId));
				idPool.returnId(connId);
				connection.activeClose = true;
				connection.handler.disconnect();
			}
		}
	}

	@Override
	public void onConnect() {
		closeable = timer.scheduleDelayed(() -> {
			handler.send(new MulticastPacket(0,
					MulticastPacket.EDGE_PING, 0, 0).getHead());
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		for (ClientConnection conn : connMap.values().
				toArray(new ClientConnection[0])) {
			// "conn.onDisconnect()" might change "connMap"
			conn.activeClose = true;
			conn.handler.disconnect();
		}
		if (closeable != null) {
			closeable.close();
		}
	}
}

/**
 * An edge server for the {@link OriginServer}. All the accepted connections
 * will become the virtual connections of the connected OriginServer.<p>
 * The edge connection must be connected to the OriginServer immediately,
 * after the EdgeServer created.
 * Here is the code to make an edge server working:<p><code>
 * try (Connector connector = new Connector()) {<br>
 * &nbsp;&nbsp;EdgeServer edge = new EdgeServer(connector);<br>
 * &nbsp;&nbsp;connector.add(edge, 2424);<br>
 * &nbsp;&nbsp;connector.connect(edge.getOriginConnection(), "localhost", 2323);<br>
 * &nbsp;&nbsp;connector.doEvents();<br>
 * }</code>
 */
public class EdgeServer implements ServerConnection {
	private OriginConnection origin = new OriginConnection();

	public EdgeServer(TimerHandler timer) {
		origin.timer = timer;
	}

	@Override
	public Connection get() {
		return new ClientConnection(origin);
	}

	/** @return The connection to the {@link OriginServer}. */
	public Connection getOriginConnection() {
		return origin.appendFilter(new PacketFilter(MulticastPacket.getParser()));
	}
}