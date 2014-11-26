package com.xqbase.net.misc;
import java.io.IOException;

import com.xqbase.net.Connector;
import com.xqbase.net.Filter;

public class TestBlockServer {
	public static void main(String[] args) throws IOException {
		Connector connector = new Connector();
		ForwardServer server = new ForwardServer(connector, 23, "ns2.xqbase.com", 23);
		connector.add(server);
		server.getFilterFactories().add(() -> new Filter() {
			@Override
			protected void onSend(boolean queued) {
				super.onSend(queued);
				System.out.println("Local.onSend(" + queued + "): " + getConnection());
			}
		});
		server.getRemoteFilterFactories().add(() -> new Filter() {
			@Override
			protected void onSend(boolean queued) {
				super.onSend(queued);
				System.out.println("Remote.onSend(" + queued + "): " + getConnection());
			}
		});
		connector.doEvents();
	}
}