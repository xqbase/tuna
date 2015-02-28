package com.xqbase.tuna.allinone;

import com.xqbase.tuna.packet.PacketException;
import com.xqbase.tuna.packet.PacketParser;
import com.xqbase.tuna.util.Bytes;

class AllInOnePacket {
	// Client Commands
	static final int CLIENT_PING		= 0x101;
	static final int CLIENT_AUTH		= 0x102;
	static final int CLIENT_LISTEN		= 0x105;
	// Server Commands
	static final int SERVER_PONG		= 0x201;
	static final int SERVER_AUTH_REPLY	= 0x202;
	static final int SERVER_LISTEN_REPLY = 0x105;
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

	static final int HEAD_SIZE			= 8;

	private static final int HEAD_TAG	= 0x2095;

	private static PacketParser parser = new PacketParser() {
		@Override
		public int getSize(byte[] b, int off, int len) throws PacketException {
			if (len < HEAD_SIZE) {
				return 0;
			}
			if (Bytes.toShort(b, off) != HEAD_TAG) {
				throw new PacketException("Wrong Packet Head");
			}
			return HEAD_SIZE + (Bytes.toShort(b, off + 2) & 0xFFFF);
		}
	};

	static PacketParser getParser() {
		return parser;
	}

	int size, cmd, cid;

	/** @param len */
	AllInOnePacket(byte[] b, int off, int len) {
		size = Bytes.toShort(b, off + 2) & 0xFFFF;
		cmd = Bytes.toShort(b, off + 4);
		cid = Bytes.toShort(b, off + 6) & 0xFFFF;
	}

	AllInOnePacket(int size, int command, int connId) {
		this.size = size;
		this.cmd = command;
		this.cid = connId;
	}

	void fillHead(byte[] b, int off) {
		Bytes.setShort(HEAD_TAG, b, off);
		Bytes.setShort(size, b, off + 2);
		Bytes.setShort(cmd, b, off + 4);
		Bytes.setShort(cid, b, off + 6);
	}

	byte[] getHead() {
		byte[] head = new byte[8];
		fillHead(head, 0);
		return head;
	}
}