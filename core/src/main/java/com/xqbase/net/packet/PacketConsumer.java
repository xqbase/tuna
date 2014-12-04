package com.xqbase.net.packet;

/** This interface provides the way to consume packets. */
@FunctionalInterface
public interface PacketConsumer {
	/** Consumes a packet. */
	public void accept(byte[] b, int off, int len) throws PacketException;
}