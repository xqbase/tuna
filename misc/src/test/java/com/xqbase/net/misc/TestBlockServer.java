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
				System.out.printf("Local: " + getConnection().getRemotePort() + " " + queued +
						" " + connector.getTotalBytesRecv() + " " + connector.getTotalBytesSent());
				System.out.println();
			}
		});
		server.getRemoteFilterFactories().add(() -> new Filter() {
			@Override
			protected void onSend(boolean queued) {
				super.onSend(queued);
				System.out.printf("Remote: " + getConnection().getLocalPort() + " " + queued +
						" " + connector.getTotalBytesRecv() + " " + connector.getTotalBytesSent());
				System.out.println();
			}
		});
		connector.doEvents();
	}
}