package com.xqbase.net.misc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.xqbase.net.ConnectionWrapper;
import com.xqbase.net.packet.PacketConsumer;
import com.xqbase.net.packet.PacketException;
import com.xqbase.net.packet.PacketFilter;
import com.xqbase.net.packet.PacketParser;
import com.xqbase.net.util.ByteArrayQueue;
import com.xqbase.net.util.Bytes;

/** A {@link ConnectionWrapper} which compresses application data and uncompresses network data. */
public class ZLibFilter extends PacketFilter {
	private static PacketParser parser = (b, off, len) -> {
		if (len < 4) {
			return 0;
		}
		if (Bytes.toShort(b, off + 2) % 31 != 0) {
			throw new PacketException("Wrong Packet Head");
		}
		int size = Bytes.toShort(b, off) & 0xffff;
		if (size < 4) {
			throw new PacketException("Wrong Packet Size");
		}
		return size;
	};

	/** Creates a ZLibFilter. */
	public ZLibFilter() {
		super(parser);
	}

	@Override
	public PacketConsumer getConsumer() {
		return (b, off, len) -> {
			ByteArrayQueue baq = new ByteArrayQueue();
			byte[] buffer = new byte[2048];
			try (InflaterInputStream inflater = new InflaterInputStream(new
					ByteArrayInputStream(b, off + 2, len - 2))) {
				int bytesRead;
				while ((bytesRead = inflater.read(buffer)) > 0) {
					baq.add(buffer, 0, bytesRead);
					// Prevent attack
					if (baq.length() >= 32768) {
						break;
					}
				}
			} catch (IOException e) {
				throw new PacketException(e.getMessage());
			}
			super.getConsumer().accept(baq.array(), baq.offset(), baq.length());
		};
	}

	private static final byte[] EMPTY_HEAD = new byte[] {0, 0};

	@Override
	public void send(byte[] b, int off, int len) {
		ByteArrayQueue baq = new ByteArrayQueue();
		baq.add(EMPTY_HEAD);
		try (DeflaterOutputStream dos = new
				DeflaterOutputStream(baq.getOutputStream())) {
			dos.write(b, off, len);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Bytes.setShort(baq.length(), baq.array(), baq.offset());
		super.send(baq.array(), baq.offset(), baq.length());
	}
}