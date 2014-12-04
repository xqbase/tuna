package com.xqbase.net.packet;

/** This interface provides the way to parse packets. */
@FunctionalInterface
public interface PacketParser {
	/**
	 * @return Packet size according to the beginning bytes of a packet,
	 *         should be zero if not enough bytes to determine the packet size.
	 * @throws PacketException if data cannot be parsed into a packet.
	 */
	public int getSize(byte[] b, int off, int len) throws PacketException;
}