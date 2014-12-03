package com.xqbase.net.portmap;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.xqbase.net.Client;
import com.xqbase.net.Connector;
import com.xqbase.net.Listener;
import com.xqbase.net.ListenerFactory;
import com.xqbase.net.Server;
import com.xqbase.net.packet.PacketFilter;

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

class PublicConnection extends Client {
	private MapConnection mapConn;
	private int connId;

	PublicConnection(MapConnection mapConn, int connId) {
		this.mapConn = mapConn;
		this.connId = connId;
	}

	@Override
	protected void onRecv(byte[] b, int off, int len) {
		mapConn.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_DATA, 0, len).getHead());
		mapConn.send(b, off, len);
	}

	@Override
	protected void onConnect() {
		mapConn.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_CONNECT, 0, 0).getHead());
	}

	@Override
	protected void onDisconnect() {
		mapConn.serverConn.connMap.remove(Integer.valueOf(connId));
		// Do not return connId until CLIENT_CLOSE received
		// mapConn.serverConn.idPool.returnId(connId);
		mapConn.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_DISCONNECT, 0, 0).getHead());
	}
}

class PublicServerConnection extends Server {
	private MapConnection mapConn;

	PublicServerConnection(int port, MapConnection mapConn) throws IOException {
		super(port);
		this.mapConn = mapConn;
	}

	HashMap<Integer, PublicConnection> connMap = new HashMap<>();
	IdPool idPool = new IdPool();

	@Override
	protected Client onAccept() {
		int connId = idPool.borrowId();
		PublicConnection conn = new PublicConnection(mapConn, connId);
		connMap.put(Integer.valueOf(connId), conn);
		return conn;
	}
}

class MapConnection implements Listener {
	private PortMapServer mapServer;

	long accessed = System.currentTimeMillis();
	PublicServerConnection serverConn = null;

	MapConnection(PortMapServer mapServer) {
		this.mapServer = mapServer;
		appendFilter(new PacketFilter(PortMapPacket.getParser()));
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		mapServer.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		mapServer.timeoutSet.add(this);
		PortMapPacket packet = new PortMapPacket(b, off, len);
		int command = packet.command;
		if (command == PortMapPacket.CLIENT_PING) {
			send(new PortMapPacket(0, PortMapPacket.SERVER_PONG, 0, 0).getHead());
		} else if (command == PortMapPacket.CLIENT_OPEN) {
			if (serverConn != null) {
				// throw new PacketException("Mapping Already Exists");
				disconnect();
				return;
			}
			int port = packet.port;
			if (port < mapServer.portFrom || port > mapServer.portTo) {
				// throw new PacketException("Port Not Allowed");
				disconnect();
				return;
			}
			try {
				serverConn = new PublicServerConnection(port, this);
			} catch (IOException e) {
				// throw new PacketException(e.getMessage());
				disconnect();
				return;
			}
			mapServer.connector.add(serverConn);
		} else {
			if (serverConn == null) {
				// throw new PacketException("Mapping Not Exists");
				disconnect();
				return;
			}
			int connId = packet.connId;
			if (command == PortMapPacket.CLIENT_CLOSE) {
				serverConn.idPool.returnId(connId);
				return;
			}
			PublicConnection conn = serverConn.connMap.get(Integer.valueOf(connId));
			if (conn == null) {
				return;
			}
			if (command == PortMapPacket.CLIENT_DATA) {
				if (PortMapPacket.HEAD_SIZE + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					disconnect();
					return;
				}
				conn.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
			} else {
				serverConn.connMap.remove(Integer.valueOf(connId));
				serverConn.idPool.returnId(connId);
				conn.disconnect();
			}
		}
	}

	@Override
	protected void onDisconnect() {
		mapServer.timeoutSet.remove(this);
		if (serverConn == null) {
			return;
		}
		mapServer.connector.remove(serverConn);
		for (PublicConnection conn : serverConn.connMap.values().
				toArray(new PublicConnection[0])) {
			// "conn.onDisconnect()" might change "connMap"
			conn.disconnect();
		}
	}

	@Override
	public void disconnect() {
		super.disconnect();
		onDisconnect();
	}
}

/**
 * A Port Mapping Server, which provides the mapping service for {@link PortMapClient}s.
 * This server will open public ports, which map private ports provided by PortMapClients.
 * @see PortMapClient
 */
public class PortMapServer implements ListenerFactory, AutoCloseable {
	LinkedHashSet<MapConnection> timeoutSet = new LinkedHashSet<>();
	Connector connector;

	private Executor executor;
	private ScheduledFuture<?> future;

	/**
	 * Creates a PortMapServer.
	 * @param connector - The {@link Connector} which public connections are registered to.
	 * @param port - The mapping service port which {@link PortMapClient} connects to.
	 * @param portFrom - The lowest port the client can open.
	 * @param portTo - The highest port the client can open.
	 * @param timer - The {@link ScheduledExecutorService} to clear expired connections.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	public PortMapServer(Connector connector, ScheduledExecutorService timer) throws IOException {
		this.connector = connector;
		// in main thread
		future = timer.scheduleAtFixedRate(() -> {
			// in timer thread
			executor.execute(() -> {
				// in main thread
				long now = System.currentTimeMillis();
				Iterator<MapConnection> i = timeoutSet.iterator();
				MapConnection mapConn;
				while (i.hasNext() && now > (mapConn = i.next()).accessed + 60000) {
					i.remove();
					mapConn.disconnect();
				}
			});
		}, 45000, 45000, TimeUnit.MILLISECONDS);
	}

	@Override
	public Listener onAccept() {
		MapConnection mapConn = new MapConnection(this);
		timeoutSet.add(mapConn);
		return mapConn;
	}

	@Override
	public void setExecutor(Executor executor) {
		this.executor = executor;
	}

	@Override
	public void close() {
		future.cancel(false);
	}
}