package com.xqbase.tuna.mux;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class OriginMuxConnection extends MuxServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	long accessed = System.currentTimeMillis();

	private OriginServer origin;
	private boolean authed;

	OriginMuxConnection(OriginServer origin) {
		super(origin.server, origin.context, false);
		this.origin = origin;
		authed = origin.context.test(null);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		origin.timeoutSet.remove(this);
		accessed = System.currentTimeMillis();
		origin.timeoutSet.add(this);
		MuxPacket packet = new MuxPacket(b, off);
		int cid = packet.cid;
		switch (packet.cmd) {
		case MuxPacket.CLIENT_PING:
			MuxPacket.send(handler, MuxPacket.SERVER_PONG, 0);
			return;
		case MuxPacket.CLIENT_AUTH:
			authed = origin.context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			MuxPacket.send(handler, authed ? MuxPacket.SERVER_AUTH_OK :
					MuxPacket.SERVER_AUTH_ERROR, 0);
			return;
		case MuxPacket.CONNECTION_CONNECT:
			if (authed) {
				onRecv(packet, b, off + HEAD_SIZE);
			} else {
				MuxPacket.send(handler, MuxPacket.HANDLER_DISCONNECT, cid);
				MuxPacket.send(handler, MuxPacket.SERVER_AUTH_NEED, 0);
			}
			return;
		default:
			onRecv(packet, b, off + HEAD_SIZE);
		}
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		origin.timeoutSet.remove(this);
	}
}

/**
 * An origin server can manage a large number of virtual {@link Connection}s
 * via several {@link EdgeServer}s.
 * @see EdgeServer
 */
public class OriginServer implements ServerConnection, AutoCloseable {
	Set<OriginMuxConnection> timeoutSet = new LinkedHashSet<>();
	ServerConnection server;
	MuxContext context;

	private TimerHandler.Closeable closeable;

	public OriginServer(ServerConnection server, MuxContext context) {
		this.server = server;
		this.context = context;
		closeable = context.scheduleDelayed(() -> {
			long now = System.currentTimeMillis();
			Iterator<OriginMuxConnection> i = timeoutSet.iterator();
			OriginMuxConnection mux;
			while (i.hasNext() && now > (mux = i.next()).accessed + 60000) {
				i.remove();
				mux.handler.disconnect();
			}
		}, 1000, 1000);
	}

	@Override
	public Connection get() {
		OriginMuxConnection mux = new OriginMuxConnection(this);
		timeoutSet.add(mux);
		return mux.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	@Override
	public void close() {
		closeable.close();
	}
}