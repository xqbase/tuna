package com.xqbase.tuna.multicast;

import java.io.File;
import java.util.HashMap;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.CrossDomainServer;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.misc.TestFlash;
import com.xqbase.tuna.misc.ZLibFilter;
import com.xqbase.tuna.multicast.EdgeServer;
import com.xqbase.tuna.multicast.OriginServer;

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
						public void setHandler(ConnectionHandler handler) {
							if (key != null) {
								multicastMap.put(key, handler);
							}
						}

						@Override
						public void onRecv(byte[] b, int off, int len) {
							multicastMap.get(multicastKey[0]).send(b, off, len);
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