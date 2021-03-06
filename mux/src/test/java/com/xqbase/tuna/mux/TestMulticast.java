package com.xqbase.tuna.mux;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.misc.CrossDomainServer;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.misc.ZLibFilter;
import com.xqbase.tuna.util.Bytes;

class BroadcastConnection implements Connection {
	private Set<ConnectionHandler> handlers;
	private ConnectionHandler[] multicast;
	private ConnectionHandler handler;

	public BroadcastConnection(Set<ConnectionHandler> handlers,
			ConnectionHandler[] multicast) {
		this.handlers = handlers;
		this.multicast = multicast;
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
		if (MulticastHandler.isMulticast(handler)) {
			multicast[0] = handler;
		}
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		multicast[0].send(b, off, len);
	}

	@Override
	public void onConnect(ConnectionSession session) {
		handlers.add(handler);
	}

	@Override
	public void onDisconnect() {
		handlers.remove(handler);
	}
}

public class TestMulticast {
	public static void main(String[] args) throws Exception {
		Set<ConnectionHandler> handlers = new LinkedHashSet<>();
		ConnectionHandler[] multicast = new ConnectionHandler[1];
		ServerConnection server = ((ServerConnection) () ->
				new BroadcastConnection(handlers, multicast)).
				appendFilter(() -> new DumpFilter().setDumpText(true)).
				appendFilter(ZLibFilter::new);
		server.get().setHandler(new MulticastHandler(handlers));
		byte[] authPhrase = "guest".getBytes();
		try (ConnectorImpl connector = new ConnectorImpl()) {
			MuxContext context = new MuxContext(connector, t ->
					t != null && Bytes.equals(t, authPhrase), 1048576, 0);
			try (OriginServer origin = new OriginServer(server, context)) {
				connector.add(new CrossDomainServer(new File(TestMulticast.class.
						getResource("/crossdomain.xml").toURI())), 843);
				connector.add(origin, 2323);
				EdgeServer edge = new EdgeServer(context, authPhrase);
				connector.add(edge, 2424);
				connector.connect(edge.getMuxConnection(), "127.0.0.1", 2323);
				edge = new EdgeServer(context, authPhrase);
				connector.add(edge, 2525);
				connector.connect(edge.getMuxConnection(), "127.0.0.1", 2323);
				connector.doEvents();
			}
		}
	}
}