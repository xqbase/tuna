package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.portmap.PortMapServer;

public class GuestConnection extends MuxServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	/**
	 * Creates a Guest Connection to a HostServer.
	 * @param server - The {@link ServerConnection} which private connections are registered to.
	 * @param context
	 * @param authPhrase
	 * @param publicPort - The port to open in {@link HostServer}
	 * @see PortMapServer
	 */
	public static Connection get(ServerConnection server,
			MuxContext context, byte[] authPhrase, int publicPort) {
		return new GuestConnection(server, context,	authPhrase, publicPort).
				appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	private TimerHandler.Closeable closeable = null;
	private MuxContext context;
	private byte[] authPhrase;
	private int publicPort;

	private GuestConnection(ServerConnection server,
			MuxContext context, byte[] authPhrase, int publicPort) {
		super(server, context);
		this.context = context;
		this.authPhrase = authPhrase;
		this.publicPort = publicPort;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MuxPacket packet = new MuxPacket(b, off);
		switch (packet.cmd) {
		case MuxPacket.SERVER_PONG:
		case MuxPacket.SERVER_AUTH_OK:
		case MuxPacket.SERVER_AUTH_ERROR:
			// TODO Handle Auth Failure
			return;
		case MuxPacket.SERVER_AUTH_NEED:
			if (authPhrase != null) {
				byte[] bb = new byte[HEAD_SIZE + authPhrase.length];
				System.arraycopy(authPhrase, 0, bb, HEAD_SIZE, authPhrase.length);
				MuxPacket.send(handler, bb, MuxPacket.CLIENT_AUTH, 0);
			}
			return;
		default:
			onRecv(packet, b, off + HEAD_SIZE);
		}
	}

	@Override
	public void onConnect(String localAddr, int localPort,
			String remoteAddr, int remotePort) {
		MuxPacket.send(handler, MuxPacket.CLIENT_LISTEN, publicPort);
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
}