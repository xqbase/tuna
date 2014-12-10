package com.xqbase.net.multicast;

import java.io.File;
import java.util.HashMap;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.ConnectorImpl;
import com.xqbase.net.misc.CrossDomainServer;
import com.xqbase.net.misc.DumpFilter;
import com.xqbase.net.misc.TestFlash;
import com.xqbase.net.misc.ZLibFilter;

public class TestMulticast {
	public static void main(String[] args) throws Exception {
		HashMap<Object, ConnectionHandler> multicastMap = new HashMap<>();
		Object[] multicastKey = new Object[1];
		try (
			ConnectorImpl connector = new ConnectorImpl();
			OriginServer origin = new OriginServer(connector) {
				@Override
				protected Connection getVirtual(Object key) {
					return new Connection() {
						@Override
						public void onRecv(byte[] b, int off, int len) {
							multicastMap.get(multicastKey[0]).send(b, off, len);
						}

						@Override
						public void setHandler(ConnectionHandler handler) {
							if (key != null) {
								multicastMap.put(key, handler);
							}
						}
					};
				}
			};
		) {
			connector.add(new CrossDomainServer(new File(TestFlash.class.
					getResource("/crossdomain.xml").toURI())), 843);
			origin.appendVirtualFilter(() -> new DumpFilter().setDumpText(true));
			origin.appendVirtualFilter(ZLibFilter::new);
			// "appendVirtualFilter" must be called before "getMulticast"
			multicastKey[0] = origin.getMulticast(origin.getVirtuals());
			connector.add(origin, 2323);
			EdgeServer edge = new EdgeServer(connector);
			connector.add(edge, 2424);
			connector.connect(edge.getOriginConnection(), "127.0.0.1", 2323);
			edge = new EdgeServer(connector);
			connector.add(edge, 2525);
			connector.connect(edge.getOriginConnection(), "127.0.0.1", 2323);
			connector.doEvents();
		}
	}
}