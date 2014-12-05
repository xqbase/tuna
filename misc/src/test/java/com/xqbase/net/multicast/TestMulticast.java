package com.xqbase.net.multicast;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.Connector;
import com.xqbase.net.misc.CrossDomainServer;
import com.xqbase.net.misc.DumpServerFilter;
import com.xqbase.net.misc.TestFlash;
import com.xqbase.net.misc.ZLibFilter;

public class TestMulticast {
	public static void main(String[] args) throws Exception {
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
		try (Connector connector = new Connector()) {
			connector.add(new CrossDomainServer(new File(TestFlash.class.
					getResource("/crossdomain.xml").toURI())), 843);
			ConnectionHandler[] multicast = new ConnectionHandler[1];
			OriginServer origin = new OriginServer() {
				@Override
				protected Connection getVirtual() {
					return new Connection() {
						@Override
						public void onRecv(byte[] b, int off, int len) {
							multicast[0].send(b, off, len);
						}
					};
				}
			};
			origin.appendVirtualFilter(new DumpServerFilter().setDumpText(true));
			origin.appendVirtualFilter(() -> new ZLibFilter());
			// "appendVirtualFilter" must be called before "createMulticast"
			multicast[0] = origin.createMulticast(new Iterable<Connection>() {
				@Override
				public Iterator<Connection> iterator() {
					// must call "getVirtuals" during each iteration
					return origin.getVirtuals().iterator();
				}
			});
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