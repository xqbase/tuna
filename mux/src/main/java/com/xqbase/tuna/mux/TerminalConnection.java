package com.xqbase.tuna.mux;

import java.io.IOException;
import java.net.InetAddress;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

class TerminalConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final int LOG_DEBUG = MuxContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = MuxContext.LOG_VERBOSE;

	private MuxClientConnection mux;
	private int cid, logLevel;

	boolean activeClose = false;
	ConnectionHandler handler;
	// Debug Info
	String send = "", recv = "";

	TerminalConnection(MuxClientConnection mux) {
		this.mux = mux;
		logLevel = mux.context.getLogLevel();
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
		if (logLevel >= LOG_VERBOSE) {
			Log.v("CONNECTION_QUEUE: " + size + ", #" + cid + send + mux.send);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		cid = mux.idPool.borrowId();
		if (cid < 0) {
			handler.disconnect();
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unable to Allocate Connection ID !!!");
			}
			return;
		}
		byte[] localAddrBytes, remoteAddrBytes;
		try {
			localAddrBytes = InetAddress.getByName(localAddr).getAddress();
			remoteAddrBytes = InetAddress.getByName(remoteAddr).getAddress();
		} catch (IOException e) {
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
		if (logLevel < LOG_DEBUG) {
			return;
		}
		String local = localAddr + ":" + localPort;
		String remote = remoteAddr + ":" + remotePort;
		send = ", " + remote + "<-" + local;
		recv = ", " + remote + "->" + local;
		if (logLevel >= LOG_VERBOSE) {
			Log.v("CONNECTION_CONNECT: #" + cid + recv + mux.send);
		}
	}

	@Override
	public void onDisconnect() {
		if (activeClose) {
			if (logLevel >= LOG_VERBOSE) {
				Log.v("Disconnected, #" + cid + mux.recv);
			}
			return;
		}
		if (mux.connectionMap.remove(Integer.valueOf(cid)) != null) {
			// Do not return cid until HANDLER_CLOSE received
			// mux.idPool.returnId(cid);
			MuxPacket.send(mux.handler,
					MuxPacket.CONNECTION_DISCONNECT, cid);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("CONNECTION_DISCONNECT: #" + cid + recv + mux.send);
			}
		} else if (logLevel >= LOG_DEBUG) {
			Log.v("CONNECTION_DISCONNECT: Already Disconnected, #" +
					cid + recv + mux.send);
		}
	}
}