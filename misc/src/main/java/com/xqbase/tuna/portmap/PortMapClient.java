package com.xqbase.tuna.portmap;

import java.io.IOException;
import java.util.HashMap;

import com.sun.security.ntlm.Client;
import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class PrivateConnection implements Connection {
	private ConnectionHandler clientHandler;
	private HashMap<Integer, PrivateConnection> connMap;
	private int connId;

	ConnectionHandler handler;

	PrivateConnection(ConnectionHandler clientHandler,
			HashMap<Integer, PrivateConnection> connMap, int connId) {
		this.clientHandler = clientHandler;
		this.connMap = connMap;
		this.connId = connId;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] head = new PortMapPacket(connId,
				PortMapPacket.CLIENT_DATA, 0, len).getHead();
		clientHandler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onDisconnect() {
		connMap.remove(Integer.valueOf(connId));
		clientHandler.send(new PortMapPacket(connId,
				PortMapPacket.CLIENT_DISCONNECT, 0, 0).getHead());
	}
}

class PortMapConnection implements Connection {
	private HashMap<Integer, PrivateConnection> connMap = new HashMap<>();
	private TimerHandler.Closeable closeable = null;
	private Connector connector;
	private TimerHandler timer;
	private String privateHost;
	private int publicPort, privatePort;
	private ConnectionHandler handler;

	PortMapConnection(Connector connector, TimerHandler timer,
			int publicPort, String privateHost, int privatePort) {
		this.connector = connector;
		this.timer = timer;
		this.publicPort = publicPort;
		this.privateHost = privateHost;
		this.privatePort = privatePort;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
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
			PrivateConnection connection = new PrivateConnection(handler, connMap, connId);
			// TODO bridge to a ServerConnection
			try {
				connector.connect(connection, privateHost, privatePort);
			} catch (IOException e) {
				// throw new PacketException(e.getMessage());
				disconnect();
				return;
			}
			connMap.put(Integer.valueOf(connId), connection);
			return;
		}
		// SERVER_DATA or SERVER_DISCONNECT must have a valid connId
		PrivateConnection connection = connMap.get(Integer.valueOf(connId));
		if (connection == null) {
			return;
		}
		if (command == PortMapPacket.SERVER_DATA) {
			if (PortMapPacket.HEAD_SIZE + packet.size > len) {
				// throw new PacketException("Wrong Packet Size");
				disconnect();
				return;
			}
			connection.handler.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
		} else {
			handler.send(new PortMapPacket(connId,
					PortMapPacket.CLIENT_CLOSE, 0, 0).getHead());
			connMap.remove(Integer.valueOf(connId));
			connection.handler.disconnect();
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		handler.send(new PortMapPacket(0,
				PortMapPacket.CLIENT_OPEN, publicPort, 0).getHead());
		closeable = timer.scheduleDelayed(() -> {
			handler.send(new PortMapPacket(0, PortMapPacket.CLIENT_PING, 0, 0).getHead());
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		if (closeable != null) {
			closeable.close();
		}
		for (PrivateConnection conn : connMap.values()) {
			conn.handler.disconnect();
		}
	}

	void disconnect() {
		handler.disconnect();
		onDisconnect();
	}
}

/**
 * A Port Mapping Client, which is a {@link Client} to a {@link PortMapServer}.
 * This connection will open a public port in PortMapServer, which maps a private port.
 * @see PortMapServer
 */
public class PortMapClient {
	/**
	 * Creates a Client Connection for PortMap.
	 * @param connector - The {@link Connector} which private connections are registered to.
	 * @param publicPort - The port to open in {@link PortMapServer}
	 * @param privateHost - The host of the mapped private server.
	 * @param privatePort - The port of the mapped private server.
	 * @see PortMapServer
	 */
	public static Connection getConnection(Connector connector, TimerHandler timer,
			int publicPort, String privateHost, int privatePort) {
		return new PortMapConnection(connector, timer, publicPort, privateHost,
				privatePort).appendFilter(new PacketFilter(PortMapPacket.getParser()));
	}
}