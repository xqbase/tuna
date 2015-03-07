package com.xqbase.tuna.mux;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;

class TerminalConnection implements Connection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private MuxClientConnection mux;
	private int cid;

	ConnectionHandler handler;

	TerminalConnection(MuxClientConnection mux) {
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