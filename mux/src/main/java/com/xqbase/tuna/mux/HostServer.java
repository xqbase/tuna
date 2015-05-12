package com.xqbase.tuna.mux;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class HostMuxConnection extends MuxClientConnection implements ServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	long accessed = System.currentTimeMillis();

	private Connector.Closeable closeable = null;
	private HostServer host;
	private boolean authed;

	HostMuxConnection(HostServer host, MuxContext context) {
		super(context, true);
		this.host = host;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		host.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		host.timeoutSet.add(this);
		MuxPacket packet = new MuxPacket(b, off);
		switch (packet.cmd) {
		case MuxPacket.CLIENT_PING:
			MuxPacket.send(handler, MuxPacket.SERVER_PONG, 0);
			return;
		case MuxPacket.CLIENT_AUTH:
			authed = host.context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			MuxPacket.send(handler, authed ? MuxPacket.SERVER_AUTH_OK :
					MuxPacket.SERVER_AUTH_ERROR, 0);
			return;
		case MuxPacket.CLIENT_LISTEN:
			if (!authed) {
				MuxPacket.send(handler, MuxPacket.SERVER_AUTH_NEED, 0);
				return;
			}
			if (closeable != null) {
				disconnect();
				return;
			}
			try {
				closeable = host.connector.add(this, packet.cid);
			} catch (IOException e) {
				MuxPacket.send(handler, MuxPacket.SERVER_LISTEN_ERROR, packet.cid);
			}
			return;
		default:
			if (closeable != null) {
				onRecv(packet, b, off + HEAD_SIZE);
			}
		}
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		host.timeoutSet.remove(this);
		if (closeable != null) {
			closeable.close();
		}
	}

	@Override
	public Connection get() {
		return new TerminalConnection(this);
	}
}

public class HostServer implements ServerConnection, AutoCloseable {
	LinkedHashSet<HostMuxConnection> timeoutSet = new LinkedHashSet<>();
	Connector connector;
	MuxContext context;

	private TimerHandler.Closeable closeable;

	/**
	 * Creates a Host Server for {@link GuestServer}s to open ports
	 * @param connector The {@link Connector} to accept public connections
	 */
	public HostServer(Connector connector, MuxContext context) {
		this.connector = connector;
		this.context = context;
		closeable = context.scheduleDelayed(() -> {
			long now = System.currentTimeMillis();
			Iterator<HostMuxConnection> i = timeoutSet.iterator();
			HostMuxConnection guest;
			while (i.hasNext() && now > (guest = i.next()).accessed + 60000) {
				i.remove();
				guest.disconnect();
			}
		}, 1000, 1000);
	}

	@Override
	public Connection get() {
		HostMuxConnection guest = new HostMuxConnection(this, context);
		timeoutSet.add(guest);
		return guest.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	@Override
	public void close() {
		closeable.close();
	}
}