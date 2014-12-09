package com.xqbase.net.multicast;

import java.io.IOException;

import com.xqbase.net.ConnectionWrapper;
import com.xqbase.net.Connector;
import com.xqbase.net.misc.DumpFilter;

public class TestEdge {
	public static void main(String[] args) throws IOException {
		try (Connector connector = new Connector()) {
			EdgeServer edge = new EdgeServer(connector);
			connector.add(edge, 2626);
			connector.connect(edge.getOriginConnection().
					appendFilter(new DumpFilter()).appendFilter(new ConnectionWrapper() {
				@Override
				public void onDisconnect() {
					super.onDisconnect();
					connector.interrupt();
				}
			}), "127.0.0.1", 2323);
			connector.doEvents();
		}
	}
}