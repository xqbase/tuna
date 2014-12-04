package com.xqbase.net.packet;

import java.io.IOException;
import java.io.OutputStream;

import com.xqbase.net.util.ByteArrayQueue;

/** An {@link OutputStream} to parse and consume packets. */ 
public class PacketOutputStream extends OutputStream {
	private int packetSize = 0;
	private ByteArrayQueue baq = new ByteArrayQueue();
	private PacketParser parser;
	private PacketConsumer consumer;

	/** Creates a PacketReader with a given parser and a given consumer. */
	public PacketOutputStream(PacketParser parser, PacketConsumer consumer) {
		this.parser = parser;
		this.consumer = consumer;
	}

	@Override
	public void write(int b) throws IOException {
		write(new byte[] {(byte) b});
	}

	/**
	 * Parse packets when data arrived. This routine will consume every successfully
	 * parsed packet by calling {@link PacketConsumer#accept(byte[], int, int)}.
	 */
	@Override
	public void write(byte[] b, int off, int len) throws PacketException {
		baq.add(b, off, len);
		while (packetSize > 0 || (packetSize = parser.getSize(baq.array(),
				baq.offset(), baq.length())) > 0) {
			if (baq.length() < packetSize) {
				return;
			}
			consumer.accept(baq.array(), baq.offset(), packetSize);
			baq.remove(packetSize);
			packetSize = 0;
		}
	}
}