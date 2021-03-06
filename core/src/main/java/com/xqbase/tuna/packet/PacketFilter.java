package com.xqbase.tuna.packet;

import com.xqbase.tuna.ConnectionFilter;

/**
 * A filter that makes Connection.onRecv() get complete packets.<p>
 * Note that this filter will close a connection when {@link PacketException} is thrown
 */
public class PacketFilter extends ConnectionFilter {
	private PacketOutputStream out;

	/** Creates a PacketFilter with a given parser. */
	public PacketFilter(PacketParser parser) {
		out = new PacketOutputStream(parser, getConsumer());
	}

	public PacketConsumer getConsumer() {
		return (b, off, len) -> super.onRecv(b, off, len);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		try {
			out.write(b, off, len);
		} catch (PacketException e) {
			disconnect();
			onDisconnect();
		}
	}
}