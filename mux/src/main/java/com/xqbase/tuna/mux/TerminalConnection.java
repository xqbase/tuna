package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ssl.SSLConnectionSession;
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
	public void onConnect(ConnectionSession session) {
		cid = mux.idPool.borrowId();
		if (cid < 0) {
			handler.disconnect();
			if (logLevel >= LOG_DEBUG) {
				Log.d("Unable to Allocate Connection ID !!!");
			}
			return;
		}
		byte[] localAddrBytes = session.getLocalSocketAddress().getAddress().getAddress();
		byte[] remoteAddrBytes = session.getRemoteSocketAddress().getAddress().getAddress();
		int len = HEAD_SIZE + 5 + localAddrBytes.length + remoteAddrBytes.length;
		byte[] b;
		if (session instanceof SSLConnectionSession) {
			byte[] sslBytes = new MuxSSLSession(((SSLConnectionSession) session).
					getSSLSession()).getEncoded();
			b = new byte[len + sslBytes.length];
			System.arraycopy(sslBytes, 0, b, len, sslBytes.length);
		} else {
			b = new byte[len];
		}
		b[HEAD_SIZE] = (byte) ((localAddrBytes.length == 16 ? MuxPacket.SESSION_LOCAL_IPV6 : 0) +
				(remoteAddrBytes.length == 16 ? MuxPacket.SESSION_REMOTE_IPV6 : 0) +
				(session instanceof SSLConnectionSession ? MuxPacket.SESSION_SSL : 0));
		System.arraycopy(localAddrBytes, 0, b, HEAD_SIZE + 1, localAddrBytes.length);
		Bytes.setShort(session.getLocalPort(), b, HEAD_SIZE + 1 + localAddrBytes.length);
		System.arraycopy(remoteAddrBytes, 0, b,
				HEAD_SIZE + 3 + localAddrBytes.length, localAddrBytes.length);
		Bytes.setShort(session.getRemotePort(), b, HEAD_SIZE + 3 +
				localAddrBytes.length + remoteAddrBytes.length);
		MuxPacket.send(mux.handler, b, MuxPacket.CONNECTION_CONNECT, cid);
		mux.connectionMap.put(Integer.valueOf(cid), this);
		if (logLevel < LOG_DEBUG) {
			return;
		}
		String local = session.getLocalAddr() + ":" + session.getLocalPort();
		String remote = session.getRemoteAddr() + ":" + session.getRemotePort();
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