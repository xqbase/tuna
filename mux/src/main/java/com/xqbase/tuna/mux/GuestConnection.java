package com.xqbase.tuna.mux;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;

import com.sun.security.ntlm.Client;
import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.portmap.PortMapServer;
import com.xqbase.tuna.util.Bytes;

class ReversedVirtualHandler implements VirtualHandler {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private GuestConnection mux;
	private int cid;

	public ReversedVirtualHandler(GuestConnection mux, int cid) {
		this.mux = mux;
		this.cid = cid;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		MuxPacket.send(mux.handler, bb, MuxPacket.HANDLER_SEND, cid);
	}

	@Override
	public void setBufferSize(int bufferSize) {
		byte[] b = new byte[HEAD_SIZE + 2];
		Bytes.setShort(bufferSize, b, HEAD_SIZE);
		MuxPacket.send(mux.handler, b, MuxPacket.HANDLER_BUFFER, cid);
	}

	@Override
	public void disconnect() {
		mux.connectionMap.remove(Integer.valueOf(cid));
		MuxPacket.send(mux.handler, MuxPacket.HANDLER_DISCONNECT, cid);
	}

	@Override
	public ConnectionHandler getMuxHandler() {
		return mux.handler;
	}

	@Override
	public int getConnectionID() {
		return cid;
	}	
}

/**
 * A Port Mapping Client, which is a {@link Client} to a {@link PortMapServer}.
 * This connection will open a public port in PortMapServer, which maps a private port.
 * @see PortMapServer
 */
public class GuestConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final Connection[] EMPTY_CONNECTIONS = new Connection[0];

	/**
	 * Creates a Guest Connection to a HostServer.
	 * @param server - The {@link ServerConnection} which private connections are registered to.
	 * @param context
	 * @param authPhrase
	 * @param publicPort - The port to open in {@link HostServer}
	 * @see PortMapServer
	 */
	public static Connection get(ServerConnection server,
			MuxContext context, byte[] authPhrase, int publicPort) {
		return new GuestConnection(server, context,	authPhrase, publicPort).
				appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	private TimerHandler.Closeable closeable = null;
	private boolean[] queued = {false};
	private ServerConnection server;
	private MuxContext context;
	private byte[] authPhrase;
	private int publicPort;

	HashMap<Integer, Connection> connectionMap = new HashMap<>();
	ConnectionHandler handler;

	private GuestConnection(ServerConnection server,
			MuxContext context, byte[] authPhrase, int publicPort) {
		this.server = server;
		this.context = context;
		this.authPhrase = authPhrase;
		this.publicPort = publicPort;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MuxPacket packet = new MuxPacket(b, off);
		int cid = packet.cid;
		switch (packet.cmd) {
		case MuxPacket.SERVER_PONG:
		case MuxPacket.SERVER_AUTH_OK:
		case MuxPacket.SERVER_AUTH_ERROR:
			// TODO Handle Auth Failure
			return;
		case MuxPacket.SERVER_AUTH_NEED:
			if (authPhrase != null) {
				byte[] bb = new byte[HEAD_SIZE + authPhrase.length];
				System.arraycopy(authPhrase, 0, bb, HEAD_SIZE, authPhrase.length);
				MuxPacket.send(handler, bb, MuxPacket.CLIENT_AUTH, 0);
			}
			return;
		case MuxPacket.CONNECTION_CONNECT:
			if (connectionMap.containsKey(Integer.valueOf(cid))) {
				handler.disconnect();
				onDisconnect();
				return;
			}
			Connection connection = server.get();
			String localAddr, remoteAddr;
			int localPort, remotePort;
			if (packet.size == 12 || packet.size == 36) {
				int addrLen = packet.size / 2 - 2;
				try {
					localAddr = InetAddress.getByAddress(Bytes.
							sub(b, off + HEAD_SIZE, addrLen)).getHostAddress();
					remoteAddr = InetAddress.getByAddress(Bytes.
							sub(b, off + HEAD_SIZE + addrLen + 2, addrLen)).getHostAddress();
				} catch (IOException e) {
					localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				}
				localPort = Bytes.toShort(Bytes.
						sub(b, off + HEAD_SIZE + addrLen, 2)) & 0xFFFF;
				remotePort = Bytes.toShort(Bytes.
						sub(b, off + HEAD_SIZE + addrLen * 2 + 2, 2)) & 0xFFFF;
			} else {
				localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				localPort = remotePort = 0;
			}
			ReversedVirtualHandler virtualHandler = new ReversedVirtualHandler(this, cid);
			connection.setHandler(virtualHandler);
			connectionMap.put(Integer.valueOf(cid), connection);
			connection.onConnect(localAddr, localPort, remoteAddr, remotePort);
			return;
		default:
			connection = connectionMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case MuxPacket.CONNECTION_RECV:
				if (packet.size > 0) {
					connection.onRecv(b, off + HEAD_SIZE, packet.size);
				}
				return;
			case MuxPacket.CONNECTION_QUEUE:
				if (packet.size >= 4) {
					connection.onQueue(Bytes.toInt(b, off + HEAD_SIZE));
				}
				return;
			case MuxPacket.CONNECTION_DISCONNECT:
				connectionMap.remove(Integer.valueOf(cid));
				MuxPacket.send(handler, MuxPacket.HANDLER_CLOSE, cid);
				connection.onDisconnect();
			}
		}
	}

	@Override
	public void onQueue(int size) {
		if (!context.isQueueChanged(size, queued)) {
			return;
		}
		// Tell all virtual connections that mux is congested or smooth 
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connecton.onQueue()" might change "connectionMap"
			connection.onQueue(size);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		MuxPacket.send(handler, MuxPacket.CLIENT_LISTEN, publicPort);
		closeable = context.scheduleDelayed(() -> {
			MuxPacket.send(handler, MuxPacket.CLIENT_PING, 0);
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		if (closeable != null) {
			closeable.close();
		}
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connection.onDisconnect()" might change "connectionMap"
			connection.onDisconnect();
		}
	}
}