package com.xqbase.net.portmap;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.sun.security.ntlm.Client;
import com.xqbase.net.Connector;
import com.xqbase.net.Filter;
import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.packet.PacketFilter;
import com.xqbase.net.util.Bytes;

class PrivateListener implements Listener {
	private PortMapClient client;
	private int connId;

	Handler handler;

	PrivateListener(PortMapClient client, int connId) {
		this.client = client;
		this.connId = connId;
	}

	@Override
	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] head = new PortMapPacket(connId,
				PortMapPacket.CLIENT_DATA, 0, len).getHead();
		client.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void onDisconnect() {
		client.connMap.remove(Integer.valueOf(connId));
		client.send(new PortMapPacket(connId,
				PortMapPacket.CLIENT_DISCONNECT, 0, 0).getHead());
	}
}

/**
 * A Port Mapping Client, which is a {@link Client} to a {@link PortMapServer}.
 * This connection will open a public port in PortMapServer, which maps a private port.
 * @see PortMapServer
 */
public class PortMapClient extends Filter {
	HashMap<Integer, PrivateListener> connMap = new HashMap<>();

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
		setListener(new Listener() {
			private ScheduledFuture<?> future = null;

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
					PrivateListener conn = new PrivateListener(PortMapClient.this, connId);
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
				PrivateListener conn = connMap.get(Integer.valueOf(connId));
				if (conn == null) {
					return;
				}
				if (command == PortMapPacket.SERVER_DATA) {
					if (PortMapPacket.HEAD_SIZE + packet.size > len) {
						// throw new PacketException("Wrong Packet Size");
						disconnect();
						return;
					}
					conn.handler.send(b, off + PortMapPacket.HEAD_SIZE, packet.size);
				} else {
					send(new PortMapPacket(connId,
							PortMapPacket.CLIENT_CLOSE, 0, 0).getHead());
					connMap.remove(Integer.valueOf(connId));
					conn.handler.disconnect();
				}
			}

			@Override
			public void onConnect() {
				send(new PortMapPacket(0,
						PortMapPacket.CLIENT_OPEN, publicPort, 0).getHead());
				// in main thread
				future = timer.scheduleAtFixedRate(() -> {
					// in timer thread
					execute(() -> {
						// in main thread
						send(new PortMapPacket(0, PortMapPacket.CLIENT_PING, 0, 0).getHead());
					});
				}, 45000, 45000, TimeUnit.MILLISECONDS);
			}

			@Override
			public void onDisconnect() {
				if (future != null) {
					future.cancel(false);
					future = null;
				}
				for (PrivateListener conn : connMap.values()) {
					conn.handler.disconnect();
				}
			}

			void disconnect() {
				PortMapClient.this.disconnect();
				onDisconnect();
			}
		}.appendFilter(new PacketFilter(PortMapPacket.getParser())));
	}
}