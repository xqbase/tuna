package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;
import com.xqbase.tuna.util.Bytes;

/**
 * An edge server for the {@link OriginServer}. All the accepted connections
 * will become the virtual connections of the connected OriginServer.<p>
 * The edge connection must be connected to the OriginServer immediately,
 * before the EdgeServer added into a {@link Connector}.<p>
 * For detailed usage, see {@link com.xqbase.tuna.cli.Edge} in github.com
 */
public class EdgeServer extends MuxClientConnection implements ServerConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private TimerHandler.Closeable closeable = null;
	private byte[] authPhrase = null;
	private MuxContext context;

	/** 
	 * Creates an Edge Server to an {@link OriginServer}<p>
	 * <b>WARNING: be sure to connect with {@link #getMuxConnection()}</b>
	 */
	public EdgeServer(MuxContext context) {
		super(context, false);
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

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MuxPacket packet = new MuxPacket(b, off);
		switch (packet.cmd) {
		case MuxPacket.SERVER_PONG:
		case MuxPacket.SERVER_AUTH_OK:
			return;
		case MuxPacket.SERVER_AUTH_ERROR:
			context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			return;
		case MuxPacket.SERVER_AUTH_NEED:
			if (authPhrase == null) {
				context.test(Bytes.sub(b, off + HEAD_SIZE, packet.size));
			} else {
				auth();
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

	@Override
	public Connection get() {
		return new TerminalConnection(this);
	}
}