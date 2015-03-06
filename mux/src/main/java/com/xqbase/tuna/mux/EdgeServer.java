package com.xqbase.tuna.mux;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class TerminalConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private EdgeMuxConnection mux;
	private int cid;

	ConnectionHandler handler;

	TerminalConnection(EdgeMuxConnection mux) {
		this.mux = mux;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		MuxPacket.send(mux.handler, bb, MuxPacket.CONNECTION_RECV, cid);
	}

	@Override
	public void onQueue(int size) {
		byte[] bb = new byte[HEAD_SIZE + 4];
		Bytes.setInt(size, bb, HEAD_SIZE);
		MuxPacket.send(mux.handler, bb, MuxPacket.CONNECTION_QUEUE, cid);
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		cid = mux.idPool.borrowId();
		if (cid < 0) {
			handler.disconnect();
			return;
		}
		byte[] localAddrBytes, remoteAddrBytes;
		try {
			localAddrBytes = InetAddress.getByName(localAddr).getAddress();
			remoteAddrBytes = InetAddress.getByName(remoteAddr).getAddress();
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
		byte[] b = new byte[HEAD_SIZE + localAddrBytes.length + remoteAddrBytes.length + 4];
		System.arraycopy(localAddrBytes, 0, b, HEAD_SIZE, localAddrBytes.length);
		Bytes.setShort(localPort, b, HEAD_SIZE + localAddrBytes.length);
		System.arraycopy(remoteAddrBytes, 0, b,
				HEAD_SIZE + localAddrBytes.length + 2, localAddrBytes.length);
		Bytes.setShort(remotePort, b, HEAD_SIZE +
				localAddrBytes.length + 2 + remoteAddrBytes.length);
		MuxPacket.send(mux.handler, b, MuxPacket.CONNECTION_CONNECT, cid);
		mux.connectionMap.put(Integer.valueOf(cid), this);
	}

	boolean activeClose = false;

	@Override
	public void onDisconnect() {
		if (activeClose) {
			return;
		}
		mux.connectionMap.remove(Integer.valueOf(cid));
		// Do not return cid until HANDLER_CLOSE received
		// mux.idPool.returnId(cid);
		MuxPacket.send(mux.handler,
				MuxPacket.CONNECTION_DISCONNECT, cid);
	}
}

class EdgeMuxConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final TerminalConnection[]
			EMPTY_CONNECTIONS = new TerminalConnection[0];

	private TimerHandler.Closeable closeable = null;
	private boolean[] queued = {false};
	private MuxContext context;

	HashMap<Integer, TerminalConnection> connectionMap = new HashMap<>();
	byte[] authPhrase = null;
	IdPool idPool = new IdPool();
	ConnectionHandler handler;

	EdgeMuxConnection(MuxContext context) {
		this.context = context;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MuxPacket packet = new MuxPacket(b, off);
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
		case MuxPacket.HANDLER_MULTICAST:
			int numConns = packet.cid;
			int dataOff = off + HEAD_SIZE + numConns * 2;
			int dataLen = packet.size - numConns * 2;
			if (dataLen <= 0) {
				return;
			}
			for (int i = 0; i < numConns; i ++) {
				int cid = Bytes.toShort(b, off + HEAD_SIZE + i * 2) & 0xFFFF;
				TerminalConnection connection = connectionMap.get(Integer.valueOf(cid));
				if (connection != null) {
					connection.handler.send(b, dataOff, dataLen);
				}
			}
			return;
		case MuxPacket.HANDLER_CLOSE:
			idPool.returnId(packet.cid);
			return;
		default:
			int cid = packet.cid;
			TerminalConnection connection = connectionMap.get(Integer.valueOf(cid));
			if (connection == null) {
				return;
			}
			switch (packet.cmd) {
			case MuxPacket.HANDLER_SEND:
				if (packet.size > 0) {
					connection.handler.send(b, off + HEAD_SIZE, packet.size);
				}
				return;
			case MuxPacket.HANDLER_BUFFER:
				if (packet.size >= 2) {
					connection.handler.setBufferSize(Bytes.
							toShort(b, off + HEAD_SIZE) & 0xFFFF);
				}
				return;
			case MuxPacket.HANDLER_DISCONNECT:
				connectionMap.remove(Integer.valueOf(cid));
				idPool.returnId(cid);
				connection.activeClose = true;
				connection.handler.disconnect();
				return;
			}
		}
	}

	@Override
	public void onQueue(int size) {
		if (!context.isQueueChanged(size, queued)) {
			return;
		}
		int bufferSize = size == 0 ? 0 : Connection.MAX_BUFFER_SIZE;
		// block or unblock all "TerminateConnection"s when mux is congested or smooth
		for (TerminalConnection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "setBuferSize" might change "connectionMap"
			connection.handler.setBufferSize(bufferSize);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		closeable = context.scheduleDelayed(() -> {
			MuxPacket.send(handler, MuxPacket.CLIENT_PING, 0);
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		for (TerminalConnection conn : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "conn.onDisconnect()" might change "connectionMap"
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
 * before the EdgeServer added into a {@link Connector}.<p>
 * For detailed usage, see {@link com.xqbase.tuna.cli.Edge} in github.com
 */
public class EdgeServer implements ServerConnection {
	private EdgeMuxConnection mux;

	public EdgeServer(MuxContext context) {
		mux = new EdgeMuxConnection(context);
	}

	@Override
	public Connection get() {
		return new TerminalConnection(mux);
	}

	/** @return The connection to the {@link OriginServer}. */
	public Connection getMuxConnection() {
		return mux.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	public void setAuthPhrase(byte[] authPhrase) {
		mux.authPhrase = authPhrase;
	}
}