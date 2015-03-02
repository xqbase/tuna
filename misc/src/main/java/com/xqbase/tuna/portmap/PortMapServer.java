package com.xqbase.tuna.portmap;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
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

class PublicConnection implements Connection {
	private MapConnection map;
	private int connId;

	ConnectionHandler handler;

	PublicConnection(MapConnection map, int connId) {
		this.map = map;
		this.connId = connId;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] head = new PortMapPacket(connId,
				PortMapPacket.SERVER_DATA, 0, len).getHead();
		map.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		map.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_CONNECT, 0, 0).getHead());
	}

	@Override
	public void onDisconnect() {
		map.publicServer.connMap.remove(Integer.valueOf(connId));
		// Do not return connId until CLIENT_CLOSE received
		// map.publicServer.idPool.returnId(connId);
		map.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_DISCONNECT, 0, 0).getHead());
	}
}

class PublicServer implements ServerConnection {
	private MapConnection map;

	PublicServer(MapConnection map) {
		this.map = map;
	}

	HashMap<Integer, PublicConnection> connMap = new HashMap<>();
	IdPool idPool = new IdPool();

	@Override
	public Connection get() {
		int connId = idPool.borrowId();
		PublicConnection connection = new PublicConnection(map, connId);
		connMap.put(Integer.valueOf(connId), connection);
		return connection;
	}
}

class MapConnection implements Connection {
	private static final PublicConnection[]
			EMPTY_CONNECTIONS = new PublicConnection[0];

	private PortMapServer mapServer;
	private Connector.Closeable publicCloseable;

	ConnectionHandler handler;
	PublicServer publicServer = null;
	long accessed = System.currentTimeMillis();

	MapConnection(PortMapServer mapServer) {
		this.mapServer = mapServer;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		mapServer.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		mapServer.timeoutSet.add(this);
		PortMapPacket packet = new PortMapPacket(b, off, len);
		int command = packet.command;
		if (command == PortMapPacket.CLIENT_PING) {
			handler.send(new PortMapPacket(0, PortMapPacket.SERVER_PONG, 0, 0).getHead());
		} else if (command == PortMapPacket.CLIENT_OPEN) {
			if (publicServer != null) {
				// throw new PacketException("Mapping Already Exists");
				disconnect();
				return;
			}
			publicServer = new PublicServer(this);
			try {
				publicCloseable = mapServer.connector.add(publicServer, packet.port);
			} catch (IOException e) {
				// throw new PacketException(e.getMessage());
				disconnect();
				return;
			}
		} else {
			if (publicServer == null) {
				// throw new PacketException("Mapping Not Exists");
				disconnect();
				return;
			}
			int connId = packet.connId;
			if (command == PortMapPacket.CLIENT_CLOSE) {
				publicServer.idPool.returnId(connId);
				return;
			}
			PublicConnection connection = publicServer.connMap.get(Integer.valueOf(connId));
			if (connection == null) {
				return;
			}
			if (command == PortMapPacket.CLIENT_DATA) {
				if (PortMapPacket.HEAD_SIZE + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					disconnect();
					return;
				}
				connection.handler.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
			} else {
				publicServer.connMap.remove(Integer.valueOf(connId));
				publicServer.idPool.returnId(connId);
				connection.handler.disconnect();
			}
		}
	}

	@Override
	public void onDisconnect() {
		mapServer.timeoutSet.remove(this);
		if (publicServer == null) {
			return;
		}
		publicCloseable.close();
		for (PublicConnection connection : publicServer.connMap.values().
				toArray(EMPTY_CONNECTIONS)) {
			// "disconnect()" might change "connMap"
			connection.handler.disconnect();
		}
	}

	void disconnect() {
		handler.disconnect();
		onDisconnect();
	}
}

/**
 * A Port Mapping Server, which provides the mapping service for {@link PortMapClient}s.
 * This server will open public ports, which map private ports provided by PortMapClients.
 * @see PortMapClient
 */
public class PortMapServer implements ServerConnection, AutoCloseable {
	LinkedHashSet<MapConnection> timeoutSet = new LinkedHashSet<>();
	Connector connector;

	private TimerHandler.Closeable closeable;

	/**
	 * Creates a PortMapServer.
	 * @param connector - The {@link Connector} which public connections are registered to.
	 */
	public PortMapServer(Connector connector, TimerHandler timer) {
		this.connector = connector;
		closeable = timer.scheduleDelayed(() -> {
			long now = System.currentTimeMillis();
			Iterator<MapConnection> i = timeoutSet.iterator();
			MapConnection map;
			while (i.hasNext() && now > (map = i.next()).accessed + 60000) {
				i.remove();
				map.disconnect();
			}
		}, 1000, 1000);
	}

	@Override
	public Connection get() {
		MapConnection mapConnection = new MapConnection(this);
		timeoutSet.add(mapConnection);
		return mapConnection.appendFilter(new PacketFilter(PortMapPacket.getParser()));
	}

	@Override
	public void close() {
		closeable.close();
	}
}