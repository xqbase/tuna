package com.xqbase.tuna.mux;

import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;

class VirtualHandler implements ConnectionHandler {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	MuxServerConnection mux;
	int cid;

	VirtualHandler(MuxServerConnection mux, int cid) {
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
}