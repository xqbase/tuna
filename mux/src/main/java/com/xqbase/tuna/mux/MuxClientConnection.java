package com.xqbase.tuna.mux;

import java.util.HashMap;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

class MuxClientConnection implements Connection {
	private static final int LOG_DEBUG = MuxContext.LOG_DEBUG;
	private static final TerminalConnection[]
			EMPTY_CONNECTIONS = new TerminalConnection[0];

	private boolean established = false, host;
	private boolean[] queued = {false};
	private String send = "", recv = "";
	private MuxContext context;
	private int logLevel;

	boolean activeClose = false; 
	HashMap<Integer, TerminalConnection> connectionMap = new HashMap<>();
	IdPool idPool = new IdPool();
	ConnectionHandler handler;

	MuxClientConnection(MuxContext context, boolean host) {
		this.context = context;
		this.host = host;
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
			}
			return;
		case MuxPacket.HANDLER_MULTICAST:
			int numConns = cid;
			int dataOff = off + numConns * 2;
			int dataLen = packet.size - numConns * 2;
			if (dataLen <= 0) {
				return;
			}
			for (int i = 0; i < numConns; i ++) {
				cid = Bytes.toShort(b, off + i * 2) & 0xFFFF;
				connection = connectionMap.get(Integer.valueOf(cid));
				if (connection != null) {
					connection.handler.send(b, dataOff, dataLen);
				}
			}
			return;
		case MuxPacket.HANDLER_BUFFER:
			connection = connectionMap.get(Integer.valueOf(cid));
			if (connection != null && packet.size >= 2) {
				connection.handler.setBufferSize(Bytes.
						toShort(b, off) & 0xFFFF);
			}
			return;
		case MuxPacket.HANDLER_DISCONNECT:
			connection = connectionMap.remove(Integer.valueOf(cid));
			if (connection != null) {
				idPool.returnId(cid);
				connection.activeClose = true;
				connection.handler.disconnect();
			}
			return;
		case MuxPacket.HANDLER_CLOSE:
			idPool.returnId(packet.cid);
			return;
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
		if (host) {
			send = ", " + remote + " <= " + local;
			recv = ", " + remote + " => " + local;
		} else {
			send = ", " + local + " => " + remote;
			recv = ", " + local + " <= " + remote;
		}
		Log.d("Mux Connection Established" + send);
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