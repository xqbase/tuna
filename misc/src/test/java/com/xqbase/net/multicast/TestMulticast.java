package com.xqbase.net.multicast;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.Connector;
import com.xqbase.net.misc.CrossDomainServer;
import com.xqbase.net.misc.DumpFilter;
import com.xqbase.net.misc.TestFlash;
import com.xqbase.net.misc.ZLibFilter;

public class TestMulticast {
	public static void main(String[] args) throws Exception {
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
		try (Connector connector = new Connector()) {
			connector.add(new CrossDomainServer(new File(TestFlash.class.
					getResource("/crossdomain.xml").toURI())), 843);
			OriginServer origin = new OriginServer() {
				HashMap<Object, ConnectionHandler> multicastMap = new HashMap<>();
				Object key_ = getMulticast(new Iterable<Connection>() {
					@Override
					public Iterator<Connection> iterator() {
						// must call "getVirtuals" during each iteration
						return getVirtuals().iterator();
					}
				});

				@Override
				protected Connection getVirtual(Object key) {
					return new Connection() {
						@Override
						public void onRecv(byte[] b, int off, int len) {
							multicastMap.get(key_).send(b, off, len);
						}

						@Override
						public void setHandler(ConnectionHandler handler) {
							if (key != null) {
								multicastMap.put(key, handler);
							}
						}
					}.
					appendFilter(new DumpFilter().setDumpText(true)).
					appendFilter(new ZLibFilter());
				}
			};
			connector.add(origin, 2323);
			EdgeServer edge = new EdgeServer(timer);
			connector.add(edge, 2424);
			connector.connect(edge.getOriginConnection(), "127.0.0.1", 2323);
			edge = new EdgeServer(timer);
			connector.add(edge, 2525);
			connector.connect(edge.getOriginConnection(), "127.0.0.1", 2323);
			connector.doEvents();
		}
		timer.shutdown();
	}
}