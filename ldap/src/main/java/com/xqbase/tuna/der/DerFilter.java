package com.xqbase.tuna.der;

import com.xqbase.tuna.packet.PacketException;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.packet.PacketParser;

public class DerFilter extends PacketFilter {
	private static PacketParser parser = (b, off, len) -> {
		if (len < 2) {
			return 0;
		}
		if (b[off] != 0x30) {
			throw new PacketException("Not a Sequence (0x30)");
		}
		int length = b[off + 1];
		if ((length & 0x80) == 0) {
			return 2 + length;
		}
		int lenlen = length & 0x7F;
		if (lenlen > 3) {
			throw new PacketException("Too Large");
		}
		length = 0;
		for (int i = 0; i < lenlen; i ++) {
			length = (length << 8) + (b[off + 2 + i] & 0xFF);
		}
		return 2 + lenlen + length;
	};

	public DerFilter() {
		super(parser);
	}
}