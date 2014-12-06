package com.xqbase.net.portmap;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.xqbase.net.Connector;
import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.Connection;
import com.xqbase.net.ServerConnection;
import com.xqbase.net.packet.PacketFilter;
import com.xqbase.net.util.Bytes;

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
	private MapConnection mapConnection;
	private int connId;

	ConnectionHandler handler;

	PublicConnection(MapConnection mapConnection, int connId) {
		this.mapConnection = mapConnection;
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
		mapConnection.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onConnect() {
		mapConnection.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_CONNECT, 0, 0).getHead());
	}

	@Override
	public void onDisconnect() {
		mapConnection.publicServer.connMap.remove(Integer.valueOf(connId));
		// Do not return connId until CLIENT_CLOSE received
		// mapConnection.publicServer.idPool.returnId(connId);
		mapConnection.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_DISCONNECT, 0, 0).getHead());
	}
}

class PublicServer implements ServerConnection {
	private MapConnection mapConnection;

	PublicServer(MapConnection mapConnection) {
		this.mapConnection = mapConnection;
	}

	HashMap<Integer, PublicConnection> connMap = new HashMap<>();
	IdPool idPool = new IdPool();

	@Override
	public Connection get() {
		int connId = idPool.borrowId();
		PublicConnection connection = new PublicConnection(mapConnection, connId);
		connMap.put(Integer.valueOf(connId), connection);
		return connection;
	}
}

class MapConnection implements Connection {
	private PortMapServer mapServer;
	private AutoCloseable publicCloseable;

	ConnectionHandler handler;
	long accessed = System.currentTimeMillis();
	PublicServer publicServer = null;

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
		try {
			publicCloseable.close();
		} catch (Exception e) {/**/}
		for (PublicConnection connection : publicServer.connMap.values().
				toArray(new PublicConnection[0])) {
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

	private ScheduledFuture<?> future;

	/**
	 * Creates a PortMapServer.
	 * @param connector - The {@link Connector} which public connections are registered to.
	 * @param timer - The {@link ScheduledExecutorService} to clear expired connections.
	 */
	public PortMapServer(Connector connector, ScheduledExecutorService timer) {
		this.connector = connector;
		// in main thread
		future = timer.scheduleAtFixedRate(() -> {
			// in timer thread
			connector.execute(() -> {
				// in main thread
				long now = System.currentTimeMillis();
				Iterator<MapConnection> i = timeoutSet.iterator();
				MapConnection mapConn;
				while (i.hasNext() && now > (mapConn = i.next()).accessed + 60000) {
					i.remove();
					mapConn.disconnect();
				}
			});
		}, 1000, 1000, TimeUnit.MILLISECONDS);
	}

	@Override
	public Connection get() {
		MapConnection mapConnection = new MapConnection(this);
		timeoutSet.add(mapConnection);
		return mapConnection.appendFilter(new PacketFilter(PortMapPacket.getParser()));
	}

	@Override
	public void close() {
		future.cancel(false);
	}
}