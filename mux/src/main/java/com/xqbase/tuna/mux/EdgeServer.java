package com.xqbase.tuna.mux;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.TimerHandler;
import com.xqbase.tuna.packet.PacketFilter;

class EdgeMuxConnection extends MuxClientConnection {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	private TimerHandler.Closeable closeable = null;
	private MuxContext context;

	byte[] authPhrase = null;

	EdgeMuxConnection(MuxContext context) {
		super(context);
		this.context = context;
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

/**
 * An edge server for the {@link OriginServer}. All the accepted connections
 * will become the virtual connections of the connected OriginServer.<p>
 * The edge connection must be connected to the OriginServer immediately,
 * before the EdgeServer added into a {@link Connector}.<p>
 * For detailed usage, see {@link com.xqbase.tuna.cli.Edge} in github.com
 */
public class EdgeServer implements ServerConnection {
	private EdgeMuxConnection mux;

	public EdgeServer(MuxContext context) {
		mux = new EdgeMuxConnection(context);
	}

	@Override
	public Connection get() {
		return new TerminalConnection(mux);
	}

	/** @return The connection to the {@link OriginServer}. */
	public Connection getMuxConnection() {
		return mux.appendFilter(new PacketFilter(MuxPacket.PARSER));
	}

	public void setAuthPhrase(byte[] authPhrase) {
		mux.authPhrase = authPhrase;
	}
}