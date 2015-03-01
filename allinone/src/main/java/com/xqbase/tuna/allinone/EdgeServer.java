package com.xqbase.tuna.allinone;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class ClientConnection implements Connection {
	private static final int HEAD_SIZE = AiOPacket.HEAD_SIZE;

	private OriginConnection origin;
	private int cid;

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
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		AiOPacket.send(origin.handler, bb, AiOPacket.CONNECTION_RECV, cid);
	}

	@Override
	public void onQueue(int delta, int total) {
		byte[] bb = new byte[HEAD_SIZE + 8];
		Bytes.setInt(delta, bb, HEAD_SIZE);
		Bytes.setInt(total, bb, HEAD_SIZE + 4);
		AiOPacket.send(origin.handler, bb, AiOPacket.CONNECTION_QUEUE, cid);
	}

	@Override
	public void onConnect() {
		cid = origin.idPool.borrowId();
		if (cid < 0) {
			handler.disconnect();
			return;
		}
		byte[] localAddrBytes, remoteAddrBytes;
		try {
			localAddrBytes = InetAddress.getByName(handler.getLocalAddr()).getAddress();
			remoteAddrBytes = InetAddress.getByName(handler.getRemoteAddr()).getAddress();
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
		byte[] b = new byte[HEAD_SIZE + localAddrBytes.length + remoteAddrBytes.length + 4];
		System.arraycopy(localAddrBytes, 0, b, HEAD_SIZE, localAddrBytes.length);
		Bytes.setShort(handler.getLocalPort(), b, HEAD_SIZE + localAddrBytes.length);
		System.arraycopy(remoteAddrBytes, 0, b,
				HEAD_SIZE + localAddrBytes.length + 2, localAddrBytes.length);
		Bytes.setShort(handler.getRemotePort(), b, HEAD_SIZE +
				localAddrBytes.length + 2 + remoteAddrBytes.length);
		AiOPacket.send(origin.handler, b, AiOPacket.CONNECTION_CONNECT, cid);
		origin.connMap.put(Integer.valueOf(cid), this);
	}

	boolean activeClose = false;

	@Override
	public void onDisconnect() {
		if (activeClose) {
			return;
		}
		origin.connMap.remove(Integer.valueOf(cid));
		// Do not return cid until ORIGIN_CLOSE received
		// origin.idPool.returnId(cid);
		AiOPacket.send(origin.handler,
				AiOPacket.CONNECTION_DISCONNECT, cid);
	}
}

class OriginConnection implements Connection {
	private static final int HEAD_SIZE = AiOPacket.HEAD_SIZE;
	private static final ClientConnection[]
			EMPTY_CONNECTIONS = new ClientConnection[0];

	private TimerHandler.Closeable closeable = null;

	HashMap<Integer, ClientConnection> connMap = new HashMap<>();
	byte[] authPhrase = null;
	IdPool idPool = new IdPool();
	TimerHandler timer;
	ConnectionHandler handler;

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		AiOPacket packet = new AiOPacket(b, off);
		switch (packet.cmd) {
		case AiOPacket.SERVER_PONG:
		case AiOPacket.SERVER_AUTH_OK:
		case AiOPacket.SERVER_AUTH_ERROR:
			// TODO Handle Auth Failure
			return;
		case AiOPacket.SERVER_AUTH_NEED:
			if (authPhrase != null) {
				byte[] bb = new byte[HEAD_SIZE + authPhrase.length];
				System.arraycopy(authPhrase, 0, bb, HEAD_SIZE, authPhrase.length);
				AiOPacket.send(handler, bb, AiOPacket.CLIENT_AUTH, 0);
			}
			return;
		case AiOPacket.HANDLER_MULTICAST:
			int numConns = packet.cid;
			int dataOff = off + HEAD_SIZE + numConns * 2;
			int dataLen = packet.size - numConns * 2;
			if (dataLen <= 0) {
				return;
			}
			for (int i = 0; i < numConns; i ++) {
				int cid = Bytes.toShort(b, off + HEAD_SIZE + i * 2) & 0xFFFF;
				ClientConnection conn = connMap.get(Integer.valueOf(cid));
				if (conn != null) {
					conn.handler.send(b, dataOff, dataLen);
				}
			}
			return;
		case AiOPacket.HANDLER_CLOSE:
			idPool.returnId(packet.cid);
			return;
		default:
			int cid = packet.cid;
			ClientConnection connection = connMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case AiOPacket.HANDLER_SEND:
				if (packet.size > 0) {
					connection.handler.send(b, off + HEAD_SIZE, packet.size);
				}
				return;
			case AiOPacket.HANDLER_BUFFER:
				if (packet.size >= 2) {
					connection.handler.setBufferSize(Bytes.
							toShort(b, off + HEAD_SIZE) & 0xFFFF);
				}
				return;
			case AiOPacket.HANDLER_DISCONNECT:
				connMap.remove(Integer.valueOf(cid));
				idPool.returnId(cid);
				connection.activeClose = true;
				connection.handler.disconnect();
				return;
			}
		}
	}

	@Override
	public void onConnect() {
		closeable = timer.scheduleDelayed(() -> {
			AiOPacket.send(handler, AiOPacket.CLIENT_PING, 0);
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		for (ClientConnection conn : connMap.
				values().toArray(EMPTY_CONNECTIONS)) {
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
		return origin.appendFilter(new PacketFilter(AiOPacket.PARSER));
	}

	public void setAuthPhrase(byte[] authPhrase) {
		origin.authPhrase = authPhrase;
	}
}