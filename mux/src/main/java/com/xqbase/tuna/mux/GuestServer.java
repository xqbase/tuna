package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

class GuestMuxConnection extends MuxServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private long accessed = System.currentTimeMillis();
	private int pingElapse = 0;
	private TimerHandler.Closeable closeable = null;
	private byte[] authPhrase;
	private int publicPort;

	GuestMuxConnection(ServerConnection server, MuxContext context,
			byte[] authPhrase, int publicPort) {
		super(server, context, true);
		this.authPhrase = authPhrase;
		this.publicPort = publicPort;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		accessed = System.currentTimeMillis();
		MuxPacket packet = new MuxPacket(b, off);
		switch (packet.cmd) {
		case MuxPacket.SERVER_PONG:
		case MuxPacket.SERVER_AUTH_OK:
			return;
		case MuxPacket.SERVER_AUTH_ERROR:
		case MuxPacket.SERVER_AUTH_NEED:
		case MuxPacket.SERVER_LISTEN_ERROR:
			context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			return;
		default:
			onRecv(packet, b, off + HEAD_SIZE);
		}
	}

	@Override
	public void onConnect(ConnectionSession session) {
		super.onConnect(session);
		if (authPhrase != null) {
			byte[] b = new byte[HEAD_SIZE + authPhrase.length];
			System.arraycopy(authPhrase, 0, b, HEAD_SIZE, authPhrase.length);
			MuxPacket.send(handler, b, MuxPacket.CLIENT_AUTH, 0);
		}
		MuxPacket.send(handler, MuxPacket.CLIENT_LISTEN, publicPort);
		closeable = context.scheduleDelayed(() -> {
			if (System.currentTimeMillis() > accessed + 60000) {
				disconnect();
				return;
			}
			pingElapse ++;
			if (pingElapse == 45) {
				pingElapse = 0;
				MuxPacket.send(handler, MuxPacket.CLIENT_PING, 0);
			}
		}, 1000, 1000);
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		if (closeable != null) {
			closeable.close();
			closeable = null;
		}
	}
}

public class GuestServer {
	private GuestMuxConnection mux;

	/**
	 * Creates a Guest Server to a {@link HostServer}<p>
	 * @param server - The {@link ServerConnection} to consume virtual connections
	 * @param context
	 * @param publicPort - The port to open in {@link HostServer}
	 */
	public GuestServer(ServerConnection server, MuxContext context,
			byte[] authPhrase, int publicPort) {
		mux = new GuestMuxConnection(server, context, authPhrase, publicPort);
	}
	
	public Connection getMuxConnection() {
		return mux.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	public void disconnect() {
		mux.disconnect();
	}
}