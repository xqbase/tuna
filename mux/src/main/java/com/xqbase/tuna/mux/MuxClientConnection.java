package com.xqbase.tuna.mux;

import java.util.HashMap;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

class MuxClientConnection implements Connection {
	private static final int LOG_DEBUG = MuxContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = MuxContext.LOG_VERBOSE;
	private static final TerminalConnection[]
			EMPTY_CONNECTIONS = new TerminalConnection[0];

	private boolean established = false, activeClose = false, reverse;
	private int[] lastSize = {0};
	private int logLevel;

	HashMap<Integer, TerminalConnection> connectionMap = new HashMap<>();
	IdPool idPool = new IdPool();
	String send = "", recv = "";
	MuxContext context;
	ConnectionHandler handler;

	MuxClientConnection(MuxContext context, boolean reverse) {
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
		case MuxPacket.HANDLER_SEND:
			TerminalConnection connection = connectionMap.get(Integer.valueOf(cid));
			if (connection != null && packet.size > 0) {
				connection.handler.send(b, off, packet.size);
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("HANDLER_SEND: " + (connection == null ?
						"Not Found, #" : "Nothing to Send, #") + cid + recv);
			}
			return;
		case MuxPacket.HANDLER_MULTICAST:
			int numConns = cid;
			int dataOff = off + numConns * 2;
			int dataLen = packet.size - numConns * 2;
			if (dataLen <= 0) {
				if (logLevel >= LOG_DEBUG) {
					Log.d("HANDLER_MULTICAST: Nothing to Multicast");
				}
				return;
			}
			for (int i = 0; i < numConns; i ++) {
				cid = Bytes.toShort(b, off + i * 2) & 0xFFFF;
				connection = connectionMap.get(Integer.valueOf(cid));
				if (connection != null) {
					connection.handler.send(b, dataOff, dataLen);
				} else if (logLevel >= LOG_DEBUG) {
					Log.d("HANDLER_MULTICAST: Not Found, #" + cid + recv);
				}
			}
			return;
		case MuxPacket.HANDLER_BUFFER:
			connection = connectionMap.get(Integer.valueOf(cid));
			if (connection != null && packet.size >= 2) {
				int bufferSize = Bytes.toShort(b, off) & 0xFFFF;
				connection.handler.setBufferSize(bufferSize);
				if (logLevel >= LOG_VERBOSE) {
					Log.v("HANDLER_BUFFER: " + bufferSize +
							", #" + cid + connection.send + recv);
				}
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("HANDLER_BUFFER: " + (connection == null ?
						"Not Found, #" : "Missing Buffer Size, #") + cid + recv);
			}
			return;
		case MuxPacket.HANDLER_DISCONNECT:
			connection = connectionMap.remove(Integer.valueOf(cid));
			if (connection != null) {
				idPool.returnId(cid);
				connection.activeClose = true;
				connection.handler.disconnect();
				if (logLevel >= LOG_VERBOSE) {
					Log.v("HANDLER_DISCONNECT: #" + cid + connection.send + recv);
				}
			} else if (logLevel >= LOG_DEBUG) {
				Log.d("HANDLER_DISCONNECT: Not Found, #" + cid + recv);
			}
			return;
		case MuxPacket.HANDLER_CLOSE:
			idPool.returnId(packet.cid);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("HANDLER_CLOSE: #" + packet.cid + recv);
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
		int bufferSize = size == 0 ? 0 : Connection.MAX_BUFFER_SIZE;
		// block or unblock all "TerminateConnection"s when mux is congested or smooth
		for (TerminalConnection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "setBuferSize" might change "connectionMap"
			connection.handler.setBufferSize(bufferSize);
		}
		if (logLevel >= LOG_DEBUG) {
			Log.d((size == 0 ?
				"All Terminal Connections Unblocked due to Smooth Mux" :
				"All Terminal Connections Blocked due to Congested Mux (" +
				size + ")") + send);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		established = true;
		if (logLevel < LOG_DEBUG) {
			return;
		}
		String local = localAddr + ":" + localPort;
		String remote = remoteAddr + ":" + remotePort;
		if (reverse) {
			send = ", " + remote + " <= " + local;
			recv = ", " + remote + " => " + local;
		} else {
			send = ", " + local + " => " + remote;
			recv = ", " + local + " <= " + remote;
		}
		Log.d("Mux Connection Established" + (reverse ? recv : send));
	}

	@Override
	public void onDisconnect() {
		for (TerminalConnection connection : connectionMap.
				values().toArray(EMPTY_CONNECTIONS)) {
			// "conn.onDisconnect()" might change "connectionMap"
			connection.activeClose = true;
			connection.handler.disconnect();
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