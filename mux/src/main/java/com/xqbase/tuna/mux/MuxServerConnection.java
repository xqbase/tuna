package com.xqbase.tuna.mux;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLSession;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.ssl.SSLConnectionSession;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

class MuxServerConnection implements Connection {
	private static final int LOG_DEBUG = MuxContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = MuxContext.LOG_VERBOSE;
	private static final Connection[] EMPTY_CONNECTIONS = {};

	private boolean established = false, activeClose = false, reverse;
	private String recv = "";
	private int[] lastSize = {0};
	private ServerConnection server;
	private int logLevel;

	Map<Integer, VirtualConnection> connectionMap = new HashMap<>();
	String send = "";
	MuxContext context;
	ConnectionHandler handler;

	MuxServerConnection(ServerConnection server, MuxContext context, boolean reverse) {
		this.server = server;
		this.context = context;
		this.reverse = reverse;
		logLevel = context.getLogLevel();
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	void onRecv(MuxPacket packet, byte[] b, int off) {
		int cid = packet.cid;
		switch (packet.cmd) {
		case MuxPacket.CONNECTION_RECV:
			VirtualConnection connection = connectionMap.get(Integer.valueOf(cid));
			if (connection != null && packet.size > 0) {
				connection.onRecv(b, off, packet.size);
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("CONNECTION_RECV: " + (connection == null ?
						"Not Found, #" : "Nothing to Recv, #") + cid + recv);
			}
			return;
		case MuxPacket.CONNECTION_CONNECT:
			if (connectionMap.containsKey(Integer.valueOf(cid))) {
				if (logLevel >= LOG_DEBUG) {
					Log.d("CONNECTION_CONNECT: Mux Disconnected due to " +
							"Duplicated Connection, #" + cid + recv);
				}
				disconnect();
				return;
			}
			Connection connection_ = server.get();
			InetAddress localAddress, remoteAddress;
			int localPort, remotePort;
			SSLSession ssls = null;
			try {
				if (packet.size < 1) {
					throw new IOException("No Session Byte");
				}
				int sessionByte = b[off];
				int localAddrLen = 4, remoteAddrLen = 4;
				int localPortOff = off + 5;
				if ((sessionByte & MuxPacket.SESSION_LOCAL_IPV6) != 0) {
					localAddrLen += 12;
					localPortOff += 12;
				}
				int remotePortOff = localPortOff + 6;
				if ((sessionByte & MuxPacket.SESSION_REMOTE_IPV6) != 0) {
					remoteAddrLen += 12;
					remotePortOff += 12;
				}
				if (packet.size < 5 + localAddrLen + remoteAddrLen) {
					throw new IOException("Not Enough Address Data");
				}
				localAddress = InetAddress.getByAddress(Bytes.
						sub(b, off + 1, localAddrLen));
				localPort = Bytes.toShort(b, localPortOff) & 0xFFFF;
				remoteAddress = InetAddress.getByAddress(Bytes.
						sub(b, localPortOff + 2, remoteAddrLen));
				remotePort = Bytes.toShort(b, remotePortOff) & 0xFFFF;
				if ((sessionByte & MuxPacket.SESSION_SSL) != 0) {
					ssls = new MuxSSLSession(b, remotePortOff + 2,
							packet.size + off - remotePortOff - 2);
				}
			} catch (IOException e) {
				localAddress = remoteAddress = new InetSocketAddress(0).getAddress();
				localPort = remotePort = 0;
				if (logLevel >= LOG_DEBUG) {
					StringWriter sw = new StringWriter();
					PrintWriter out = new PrintWriter(sw);
					out.println("CONNECTION_CONNECT: " + e.getMessage() + ", #" + cid + recv);
					Bytes.dump(out, b, off, packet.size);
					Log.d(sw.toString());
				}
			}
			connection = new VirtualConnection(connection_, this, cid);
			connection_.setHandler(connection);
			connectionMap.put(Integer.valueOf(cid), connection);
			InetSocketAddress localIsa = new InetSocketAddress(localAddress, localPort);
			InetSocketAddress remoteIsa = new InetSocketAddress(remoteAddress, remotePort);
			connection.onConnect(ssls == null ? new ConnectionSession(localIsa, remoteIsa) :
					new SSLConnectionSession(localIsa, remoteIsa, ssls));
			if (logLevel < LOG_DEBUG) {
				return;
			}
			String local = localAddress.getHostAddress() + ":" + localPort;
			String remote = remoteAddress.getHostAddress() + ":" + remotePort;
			connection.send = ", " + remote + "<-" + local;
			connection.recv = ", " + remote + "->" + local;
			if (logLevel >= LOG_VERBOSE) {
				Log.v("CONNECTION_CONNECT: #" + cid + connection.recv + recv);
			}
			return;
		case MuxPacket.CONNECTION_QUEUE:
			connection = connectionMap.get(Integer.valueOf(cid));
			if (connection != null && packet.size >= 4) {
				int size = Bytes.toInt(b, off);
				connection.onQueue(size);
				if (logLevel >= LOG_VERBOSE) {
					Log.v("CONNECTION_QUEUE: " + size + ", " + cid + connection.recv + recv);
				}
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("CONNECTION_QUEUE: " + (connection == null ?
						"Not Found, #" : "Missing Queue Size, #") + cid + recv);
			}
			return;
		case MuxPacket.CONNECTION_DISCONNECT:
			connection = connectionMap.remove(Integer.valueOf(cid));
			if (connection != null) {
				MuxPacket.send(handler, MuxPacket.HANDLER_CLOSE, cid);
				connection.onDisconnect();
				if (logLevel >= LOG_VERBOSE) {
					Log.v("CONNECTION_DISCONNECT: #" + cid + connection.recv + recv);
				}
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("CONNECTION_DISCONNECT: Not Found, #" + cid + recv);
			}
			return;
		}
	}

	@Override
	public void onQueue(int size) {
		if (logLevel >= LOG_VERBOSE) {
			Log.v("onQueue(" + size + ")" + send);
		}
		if (!context.isQueueStatusChanged(size, lastSize)) {
			return;
		}
		// Tell all virtual connections that mux is congested or smooth 
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connecton.onQueue()" might change "connectionMap"
			connection.onQueue(size);
		}
		if (logLevel >= LOG_DEBUG) {
			Log.d((size == 0 ?
				"All Virtual Connections Unblocked due to Smooth Mux" :
				"All Virtual Connections Blocked due to Congested Mux (" +
				size + ")") + send);
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		established = true;
		if (logLevel < LOG_DEBUG) {
			return;
		}
		String local = session.getLocalAddr() + ":" + session.getLocalPort();
		String remote = session.getRemoteAddr() + ":" + session.getRemotePort();
		if (reverse) {
			send = ", " + local + " => " + remote;
			recv = ", " + local + " <= " + remote;
		} else {
			send = ", " + remote + " <= " + local;
			recv = ", " + remote + " => " + local;
		}
		Log.d("Mux Connection Established" + (reverse ? send : recv));
	}

	@Override
	public void onDisconnect() {
		for (Connection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "connecton.onDisconnect()" might change "connectionMap"
			connection.onDisconnect();
		}
		if (logLevel >= LOG_DEBUG) {
			Log.d(!established ? "Mux Connection Failed" :
					activeClose ? "Mux Connection Disconnected" + send :
					"Mux Connection Lost" + recv);
		}
	}

	void disconnect() {
		activeClose = true;
		handler.disconnect();
		onDisconnect();
	}
}