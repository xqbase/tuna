package com.xqbase.net.packet;

import com.xqbase.net.Filter;

/** 
 * A filter that makes Connection.onRecv() get complete packets.<p>
 * Note that this filter will close a connection when {@link PacketException} is thrown
 */
public class PacketFilter extends Filter implements PacketHandler {
	private PacketReader reader;

	/** Creates a PacketFilter with a given parser. */
	public PacketFilter(PacketParser parser) {
		reader = new PacketReader(parser, this);
	}

	@Override
	public void onData(byte[] b, int off, int len) {
		super.onRecv(b, off, len);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		try {
			reader.onData(b, off, len);
		} catch (PacketException e) {
			disconnect();
			onDisconnect();
		}
	}
}