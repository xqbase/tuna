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

import com.xqbase.net.Connector;
import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.ServerListener;
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

class PublicListener implements Listener {
	private MapListener mapListener;
	private int connId;

	Handler handler;

	PublicListener(MapListener mapListener, int connId) {
		this.mapListener = mapListener;
		this.connId = connId;
	}

	@Override
	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] head = new PortMapPacket(connId,
				PortMapPacket.SERVER_DATA, 0, len).getHead();
		mapListener.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onConnect() {
		mapListener.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_CONNECT, 0, 0).getHead());
	}

	@Override
	public void onDisconnect() {
		mapListener.publicServer.connMap.remove(Integer.valueOf(connId));
		// Do not return connId until CLIENT_CLOSE received
		// mapListener.publicServer.idPool.returnId(connId);
		mapListener.handler.send(new PortMapPacket(connId,
				PortMapPacket.SERVER_DISCONNECT, 0, 0).getHead());
	}
}

class PublicServer implements ServerListener {
	private MapListener mapListener;

	PublicServer(MapListener mapListener) {
		this.mapListener = mapListener;
	}

	HashMap<Integer, PublicListener> connMap = new HashMap<>();
	IdPool idPool = new IdPool();

	@Override
	public Listener get() {
		int connId = idPool.borrowId();
		PublicListener publicListener = new PublicListener(mapListener, connId);
		connMap.put(Integer.valueOf(connId), publicListener);
		return publicListener;
	}
}

class MapListener implements Listener {
	private PortMapServer mapServer;
	private AutoCloseable publicCloseable;

	Handler handler;
	long accessed = System.currentTimeMillis();
	PublicServer publicServer = null;

	MapListener(PortMapServer mapServer) {
		this.mapServer = mapServer;
	}

	@Override
	public void setHandler(Handler handler) {
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
			PublicListener publicListener = publicServer.connMap.get(Integer.valueOf(connId));
			if (publicListener == null) {
				return;
			}
			if (command == PortMapPacket.CLIENT_DATA) {
				if (PortMapPacket.HEAD_SIZE + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					disconnect();
					return;
				}
				publicListener.handler.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
			} else {
				publicServer.connMap.remove(Integer.valueOf(connId));
				publicServer.idPool.returnId(connId);
				publicListener.handler.disconnect();
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
		for (PublicListener listener : publicServer.connMap.values()) {
			listener.handler.disconnect();
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
public class PortMapServer implements ServerListener, AutoCloseable {
	LinkedHashSet<MapListener> timeoutSet = new LinkedHashSet<>();
	Connector connector;

	private Executor executor;
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
			executor.execute(() -> {
				// in main thread
				long now = System.currentTimeMillis();
				Iterator<MapListener> i = timeoutSet.iterator();
				MapListener mapConn;
				while (i.hasNext() && now > (mapConn = i.next()).accessed + 60000) {
					i.remove();
					mapConn.disconnect();
				}
			});
		}, 45000, 45000, TimeUnit.MILLISECONDS);
	}

	@Override
	public Listener get() {
		MapListener mapListener = new MapListener(this);
		timeoutSet.add(mapListener);
		return mapListener.appendFilter(new PacketFilter(PortMapPacket.getParser()));
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