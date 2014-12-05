package com.xqbase.net.misc;
import java.io.IOException;

import com.xqbase.net.Connector;
import com.xqbase.net.ConnectionWrapper;

public class TestBlockServer {
	public static void main(String[] args) throws IOException {
		try (Connector connector = new Connector()) {
			ForwardServer forward = new ForwardServer(connector, "ns2.xqbase.com", 23);
			connector.add(forward.appendFilter(() -> new ConnectionWrapper() {
				@Override
				public void onSend(boolean queued) {
					super.onSend(queued);
					System.out.println("Local.onSend(" + queued + "): " + this);
				}
			}), 23);
			forward.appendRemoteFilter(() -> new ConnectionWrapper() {
				@Override
				public void onSend(boolean queued) {
					super.onSend(queued);
					System.out.println("Remote.onSend(" + queued + "): " + this);
				}
			});
			connector.doEvents();
		}
	}
}