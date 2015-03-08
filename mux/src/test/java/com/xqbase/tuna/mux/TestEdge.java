package com.xqbase.tuna.mux;

import java.io.IOException;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.mux.EdgeServer;

public class TestEdge {
	public static void main(String[] args) throws IOException {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			EdgeServer edge = new EdgeServer(new MuxContext(connector, null, 1048576, 0));
			edge.setAuthPhrase("guest".getBytes());
			connector.add(edge, 2626);
			connector.connect(edge.getMuxConnection().
					appendFilter(new DumpFilter()).appendFilter(new ConnectionFilter() {
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