package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionWrapper;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

class VirtualConnection extends ConnectionWrapper implements ConnectionHandler {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;
	private static final int LOG_DEBUG = MuxContext.LOG_DEBUG;
	private static final int LOG_VERBOSE = MuxContext.LOG_VERBOSE;

	private int bufferSize = MAX_BUFFER_SIZE, logLevel;

	MuxServerConnection mux;
	int cid;
	// Debug Info
	String send = "", recv = "";

	VirtualConnection(Connection connection, MuxServerConnection mux, int cid) {
		super(connection);
		this.mux = mux;
		this.cid = cid;
		logLevel = mux.context.getLogLevel();
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] bb = new byte[HEAD_SIZE + len];
		System.arraycopy(b, off, bb, HEAD_SIZE, len);
		MuxPacket.send(mux.handler, bb, MuxPacket.HANDLER_SEND, cid);
	}

	@Override
	public void setBufferSize(int bufferSize) {
		if (bufferSize == this.bufferSize) {
			return;
		}
		this.bufferSize = bufferSize;
		byte[] b = new byte[HEAD_SIZE + 2];
		Bytes.setShort(bufferSize, b, HEAD_SIZE);
		MuxPacket.send(mux.handler, b, MuxPacket.HANDLER_BUFFER, cid);
		if (logLevel >= LOG_VERBOSE) {
			Log.v("HANDLER_BUFFER: " + bufferSize + ", #" + cid + send + mux.send);
		}
	}

	@Override
	public void disconnect() {
		if (mux.connectionMap.remove(Integer.valueOf(cid)) != null) {
			MuxPacket.send(mux.handler, MuxPacket.HANDLER_DISCONNECT, cid);
			if (logLevel >= LOG_VERBOSE) {
				Log.v("HANDLER_DISCONNECT: #" + cid + send + mux.send);
			}
		} else if (logLevel >= LOG_DEBUG) {
			Log.v("HANDLER_DISCONNECT: Already Disconnected, #" +
					cid + send + mux.send);
		}
	}
}