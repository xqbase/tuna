package com.xqbase.net.portmap;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.xqbase.net.Connection;
import com.xqbase.net.Connector;
import com.xqbase.net.packet.PacketFilter;

class PrivateConnection extends Connection {
	private PortMapClient mapClient;
	private int connId;

	PrivateConnection(PortMapClient mapClient, int connId) {
		this.mapClient = mapClient;
		this.connId = connId;
	}

	@Override
	protected void onRecv(byte[] b, int off, int len) {
		mapClient.send(new PortMapPacket(connId,
				PortMapPacket.CLIENT_DATA, 0, len).getHead());
		mapClient.send(b, off, len);
	}

	boolean activeClose = false;

	@Override
	protected void onDisconnect() {
		if (activeClose) {
			return;
		}
		mapClient.connMap.remove(Integer.valueOf(connId));
		mapClient.send(new PortMapPacket(connId,
				PortMapPacket.CLIENT_DISCONNECT, 0, 0).getHead());
	}
}

/**
 * A Port Mapping Client, which is a {@link Connection} to a {@link PortMapServer}.
 * This connection will open a public port in PortMapServer, which maps a private port.
 * @see PortMapServer
 */
public class PortMapClient extends Connection {
	HashMap<Integer, PrivateConnection> connMap = new HashMap<>();

	private Connector connector;
	private String privateHost;
	private int publicPort, privatePort;
	private ScheduledExecutorService timer;
	private ScheduledFuture<?> future = null;

	/**
	 * Creates a PortMapClient.
	 * @param connector - The {@link Connector} which private connections are registered to.
	 * @param publicPort - The port to open in {@link PortMapServer}
	 * @param privateHost - The host of the mapped private server.
	 * @param privatePort - The port of the mapped private server.
	 * @param timer - The {@link ScheduledExecutorService} to send heart beat packet.
	 * @see PortMapServer
	 */
	public PortMapClient(Connector connector, int publicPort, String privateHost,
			int privatePort, ScheduledExecutorService timer) {
		this.connector = connector;
		this.publicPort = publicPort;
		this.privateHost = privateHost;
		this.privatePort = privatePort;
		this.timer = timer;
		appendFilter(new PacketFilter(PortMapPacket.getParser()));
	}

	@Override
	protected void onRecv(byte[] b, int off, int len) {
		PortMapPacket packet = new PortMapPacket(b, off, len);
		int connId = packet.connId;
		int command = packet.command;
		if (command == PortMapPacket.SERVER_PONG) {
			return;
		}
		if (command == PortMapPacket.SERVER_CONNECT) {
			if (connMap.containsKey(Integer.valueOf(connId))) {
				// throw new PacketException("#" + connId + " Already Exists");
				disconnect();
				return;
			}
			PrivateConnection conn = new PrivateConnection(this, connId);
			try {
				connector.connect(conn, privateHost, privatePort);
			} catch (IOException e) {
				// throw new PacketException(e.getMessage());
				disconnect();
				return;
			}
			connMap.put(Integer.valueOf(connId), conn);
			return;
		}
		// SERVER_DATA or SERVER_DISCONNECT must have a valid connId
		PrivateConnection conn = connMap.get(Integer.valueOf(connId));
		if (conn == null) {
			return;
		}
		if (command == PortMapPacket.SERVER_DATA) {
			if (PortMapPacket.HEAD_SIZE + packet.size > len) {
				// throw new PacketException("Wrong Packet Size");
				disconnect();
				return;
			}
			conn.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
		} else {
			send(new PortMapPacket(connId,
					PortMapPacket.CLIENT_CLOSE, 0, 0).getHead());
			connMap.remove(Integer.valueOf(connId));
			conn.activeClose = true;
			conn.disconnect();
		}
	}

	@Override
	protected void onConnect() {
		send(new PortMapPacket(0,
				PortMapPacket.CLIENT_OPEN, publicPort, 0).getHead());
		// in main thread
		future = timer.scheduleAtFixedRate(() -> {
			// in timer thread
			invokeLater(() -> {
				// in main thread
				send(new PortMapPacket(0, PortMapPacket.CLIENT_PING, 0, 0).getHead());
			});
		}, 45000, 45000, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void onDisconnect() {
		if (future != null) {
			future.cancel(false);
			future = null;
		}
		for (PrivateConnection conn : connMap.values().
				toArray(new PrivateConnection[0])) {
			// "conn.onDisconnect()" might change "connMap"
			conn.activeClose = true;
			conn.disconnect();
		}
	}
}