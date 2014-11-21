package com.xqbase.net.misc;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import com.xqbase.net.Connection;
import com.xqbase.net.Connector;
import com.xqbase.net.FilterFactory;
import com.xqbase.net.ServerConnection;

public class TestFlash {
	private static Connector newConnector() {
		return new Connector();
	}

	public static void main(String[] args) throws Exception {
		// Evade resource leak warning
		Connector connector = newConnector();
		connector.add(new CrossDomainServer(new File(TestFlash.class.
				getResource("/crossdomain.xml").toURI())));

		final LinkedHashSet<Connection> connections = new LinkedHashSet<>();
		final Connection broadcast = new Connection();
		broadcast.setWriter((b, off, len) -> {
			for (Connection connection : connections.toArray(new Connection[0])) {
				// "connection.onDisconnect()" might change "connections"
				connection.getNetWriter().write(b, off, len);
			}
			return len;
		});

		ServerConnection broadcastServer = new ServerConnection(23) {
			@Override
			protected Connection createConnection() {
				return new Connection() {
					@Override
					protected void onRecv(byte[] b, int off, int len) {
						broadcast.send(b, off, len);
					}

					@Override
					protected void onConnect() {
						connections.add(this);
					}

					@Override
					protected void onDisconnect() {
						connections.remove(this);
					}
				};
			}
		};
		connector.add(broadcastServer);

		ArrayList<FilterFactory> ffs = broadcastServer.getFilterFactories();
		// Application Data Dumped onto System.out
		ffs.add(new DumpFilterFactory().setDumpText(true));
		ffs.add(ZLibFilter::new);
		// Network Data Dumped onto System.err
		ffs.add(new DumpFilterFactory().setDumpStream(System.err));
		broadcast.appendFilters(ffs);

		DoSFilterFactory dosff = new DoSFilterFactory(60000, 65536, 60, 10);
		connector.getFilterFactories().add(dosff);
		connector.getFilterFactories().add(new IPTrustSet("127.0.0.1"));
		connector.doEvents();
	}
}