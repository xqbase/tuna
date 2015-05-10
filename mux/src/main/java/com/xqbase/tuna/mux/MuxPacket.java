package com.xqbase.tuna.mux;

import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.packet.PacketException;
import com.xqbase.tuna.packet.PacketParser;
import com.xqbase.tuna.util.Bytes;

class MuxPacket {
	private static final int HEAD_TAG	= 0x2095;

	// Client Commands
	static final int CLIENT_PING		= 0x101;
	static final int CLIENT_AUTH		= 0x111;
	static final int CLIENT_LISTEN		= 0x121;
	// Server Commands
	static final int SERVER_PONG		= 0x201;
	static final int SERVER_AUTH_NEED	= 0x212;
	static final int SERVER_AUTH_OK		= 0x218;
	static final int SERVER_AUTH_ERROR	= 0x219;
	static final int SERVER_LISTEN_OK	= 0x228;
	static final int SERVER_LISTEN_ERROR = 0x229;
	// Connection Commands
	static final int CONNECTION_RECV	= 0x301;
	static final int CONNECTION_QUEUE	= 0x312;
	static final int CONNECTION_CONNECT	= 0x315;
	static final int CONNECTION_DISCONNECT = 0x318;
	// Handler Commands
	static final int HANDLER_SEND		= 0x401;
	static final int HANDLER_MULTICAST	= 0x402;
	static final int HANDLER_BUFFER		= 0x413;
	static final int HANDLER_DISCONNECT	= 0x418;
	static final int HANDLER_CLOSE		= 0x419;

	// Session Bits
	static final int SESSION_LOCAL_IPV6	= 1;
	static final int SESSION_REMOTE_IPV6 = 2;
	static final int SESSION_SSL		= 4;

	static final int HEAD_SIZE			= 8;

	static PacketParser PARSER = (b, off, len) -> {
		if (len < HEAD_SIZE) {
			return 0;
		}
		if (Bytes.toShort(b, off) != HEAD_TAG) {
			throw new PacketException("Wrong Packet Head");
		}
		return HEAD_SIZE + (Bytes.toShort(b, off + 2) & 0xFFFF);
	};

	int size, cmd, cid;

	MuxPacket(byte[] b, int off) {
		size = Bytes.toShort(b, off + 2) & 0xFFFF;
		cmd = Bytes.toShort(b, off + 4);
		cid = Bytes.toShort(b, off + 6) & 0xFFFF;
	}

	static void send(ConnectionHandler handler, int cmd, int cid) {
		byte[] head = new byte[8];
		Bytes.setShort(HEAD_TAG, head, 0);
		Bytes.setShort(0, head, 2);
		Bytes.setShort(cmd, head, 4);
		Bytes.setShort(cid, head, 6);
		handler.send(head);
	}

	static void send(ConnectionHandler handler, byte[] b, int cmd, int cid) {
		Bytes.setShort(HEAD_TAG, b, 0);
		Bytes.setShort(b.length - HEAD_SIZE, b, 2);
		Bytes.setShort(cmd, b, 4);
		Bytes.setShort(cid, b, 6);
		handler.send(b);
	}
}