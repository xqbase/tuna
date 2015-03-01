package com.xqbase.tuna.allinone;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class DirectVirtualHandler implements VirtualHandler {
	private static final int HEAD_SIZE = AiOPacket.HEAD_SIZE;

	private EdgeConnection edge;
	private String localAddr, remoteAddr;
	private int cid, localPort, remotePort;

	DirectVirtualHandler(EdgeConnection edge, int cid, String localAddr,
			int localPort, String remoteAdd, int remotePort) {
		this.edge = edge;
		this.cid = cid;
		this.localAddr = localAddr;
		this.localPort = localPort;
		this.remoteAddr = remoteAdd;
		this.remotePort = remotePort;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		AiOPacket.send(edge.handler, bb, AiOPacket.HANDLER_SEND, cid);
	}

	@Override
	public void disconnect() {
		edge.connMap.remove(Integer.valueOf(cid));
		AiOPacket.send(edge.handler, AiOPacket.HANDLER_DISCONNECT, cid);
	}

	@Override
	public void setBufferSize(int bufferSize) {
		byte[] b = new byte[HEAD_SIZE + 2];
		Bytes.setShort(bufferSize, b, HEAD_SIZE);
		AiOPacket.send(edge.handler, b, AiOPacket.HANDLER_BUFFER, cid);
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
	public int getConnectionID() {
		return cid;
	}

	@Override
	public ConnectionHandler getAiOHandler() {
		return edge.handler;
	}
}

class EdgeConnection implements Connection {
	private static final int HEAD_SIZE = AiOPacket.HEAD_SIZE;
	private static final Connection[] EMPTY_CONNECTIONS = new Connection[0];

	HashMap<Integer, Connection> connMap = new HashMap<>();
	ConnectionHandler handler;
	long accessed = System.currentTimeMillis();

	private OriginServer origin;
	private boolean authed;

	EdgeConnection(OriginServer origin) {
		this.origin = origin;
		authed = origin.auth == null;
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
		AiOPacket packet = new AiOPacket(b, off);
		int cid = packet.cid;
		switch (packet.cmd) {
		case AiOPacket.CLIENT_PING:
			AiOPacket.send(handler, AiOPacket.SERVER_PONG, 0);
			break;
		case AiOPacket.CLIENT_AUTH:
			authed = origin.auth == null ||
					origin.auth.test(Bytes.sub(b, HEAD_SIZE, packet.size));
			AiOPacket.send(handler, authed ? AiOPacket.SERVER_AUTH_OK :
					AiOPacket.SERVER_AUTH_ERROR, 0);
			return;
		case AiOPacket.CONNECTION_CONNECT:
			if (!authed) {
				AiOPacket.send(handler, AiOPacket.SERVER_AUTH_NEED, 0);
				AiOPacket.send(handler, AiOPacket.HANDLER_DISCONNECT, cid);
				return;
			}
			if (connMap.containsKey(Integer.valueOf(cid))) {
				// throw new PacketException("#" + cid + " Already Exists");
				disconnect();
				return;
			}
			Connection connection = origin.server.get();
			String localAddr, remoteAddr;
			int localPort, remotePort;
			if (packet.size == 12 || packet.size == 36) {
				int addrLen = packet.size / 2 - 2;
				try {
					localAddr = InetAddress.getByAddress(Bytes.
							sub(b, HEAD_SIZE, addrLen)).getHostAddress();
					remoteAddr = InetAddress.getByAddress(Bytes.
							sub(b, HEAD_SIZE + addrLen + 2, addrLen)).getHostAddress();
				} catch (IOException e) {
					localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				}
				localPort = Bytes.toShort(Bytes.sub(b, HEAD_SIZE + addrLen, 2)) & 0xFFFF;
				remotePort = Bytes.toShort(Bytes.sub(b, HEAD_SIZE + addrLen * 2 + 2, 2)) & 0xFFFF;
			} else {
				localAddr = remoteAddr = Connector.ANY_LOCAL_ADDRESS;
				localPort = remotePort = 0;
			}
			DirectVirtualHandler virtualHandler = new DirectVirtualHandler(this,
					cid, localAddr, localPort, remoteAddr, remotePort);
			connection.setHandler(virtualHandler);
			connMap.put(Integer.valueOf(cid), connection);
			connection.onConnect();
			break;
		default:
			connection = connMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case AiOPacket.CONNECTION_RECV:
				if (packet.size > 0) {
					connection.onRecv(b, off + HEAD_SIZE, packet.size);
				}
				break;
			case AiOPacket.CONNECTION_QUEUE:
				if (packet.size >= 8) {
					connection.onQueue(Bytes.toInt(b, off + HEAD_SIZE),
							Bytes.toInt(b, off + HEAD_SIZE + 4));
				}
				break;
			case AiOPacket.CONNECTION_DISCONNECT:
				connMap.remove(Integer.valueOf(cid));
				AiOPacket.send(handler, AiOPacket.HANDLER_CLOSE, cid);
				connection.onDisconnect();
			}
		}
	}

	@Override
	public void onConnect() {
		if (!authed) {
			AiOPacket.send(handler, AiOPacket.SERVER_AUTH_NEED, 0);
		}
	}

	@Override
	public void onDisconnect() {
		origin.timeoutSet.remove(this);
		for (Connection connection : connMap.
				values().toArray(EMPTY_CONNECTIONS)) {
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
	Predicate<byte[]> auth;

	private TimerHandler.Closeable closeable;

	public OriginServer(ServerConnection server,
			Predicate<byte[]> auth, TimerHandler timer) {
		this.server = server;
		this.auth = auth;
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
		return edgeConnection.appendFilter(new PacketFilter(AiOPacket.PARSER));
	}

	@Override
	public void close() {
		closeable.close();
	}
}