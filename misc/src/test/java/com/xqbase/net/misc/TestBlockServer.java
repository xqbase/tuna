package com.xqbase.net.misc;
import java.io.IOException;

import com.xqbase.net.Connector;
import com.xqbase.net.Filter;
import com.xqbase.net.ServerConnection;

public class TestBlockServer {
	public static void main(String[] args) throws IOException {
		Connector connector = new Connector();
		ForwardServer forward = new ForwardServer(connector, "ns2.xqbase.com", 23);
		ServerConnection server = connector.add(forward, 23);
		server.getFilterFactories().add(() -> new Filter() {
			@Override
			public void onSend(boolean queued) {
				super.onSend(queued);
				System.out.println("Local.onSend(" + queued + "): " + this);
			}
		});
		forward.getFilterFactories().add(() -> new Filter() {
			@Override
			public void onSend(boolean queued) {
				super.onSend(queued);
				System.out.println("Remote.onSend(" + queued + "): " + this);
			}
		});
		connector.doEvents();
	}
}