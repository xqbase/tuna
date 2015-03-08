package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

public class GuestConnection extends MuxServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private TimerHandler.Closeable closeable = null;
	private MuxContext context;
	private byte[] authPhrase;
	private int publicPort;

	/**
	 * Creates a Guest Connection to a {@link HostServer}<p>
	 * <b>WARNING: be sure to connect with {@link #getMuxConnection()}</b>
	 * @param server - The {@link ServerConnection} to consume virtual connections
	 * @param context
	 * @param publicPort - The port to open in {@link HostServer}
	 */
	public GuestConnection(ServerConnection server, MuxContext context, int publicPort) {
		super(server, context, true);
		this.context = context;
		this.publicPort = publicPort;
	}

	public Connection getMuxConnection() {
		return appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	public void setAuthPhrase(byte[] authPhrase) {
		this.authPhrase = authPhrase;
	}

	private void auth() {
		byte[] b = new byte[HEAD_SIZE + authPhrase.length];
		System.arraycopy(authPhrase, 0, b, HEAD_SIZE, authPhrase.length);
		MuxPacket.send(handler, b, MuxPacket.CLIENT_AUTH, 0);
	}

	private void listen() {
		MuxPacket.send(handler, MuxPacket.CLIENT_LISTEN, publicPort);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MuxPacket packet = new MuxPacket(b, off);
		switch (packet.cmd) {
		case MuxPacket.SERVER_PONG:
		case MuxPacket.SERVER_AUTH_OK:
			return;
		case MuxPacket.SERVER_AUTH_ERROR:
		case MuxPacket.SERVER_LISTEN_ERROR:
			context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			return;
		case MuxPacket.SERVER_AUTH_NEED:
			if (authPhrase == null) {
				context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			} else {
				auth();
				listen();
			}
			return;
		default:
			onRecv(packet, b, off + HEAD_SIZE);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		super.onConnect(localAddr, localPort, remoteAddr, remotePort);
		if (authPhrase != null) {
			auth();
		}
		listen();
		closeable = context.scheduleDelayed(() -> {
			MuxPacket.send(handler, MuxPacket.CLIENT_PING, 0);
		}, 45000, 45000);
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		if (closeable != null) {
			closeable.close();
		}
	}

	@Override
	public void disconnect() {
		super.disconnect();
	}
}