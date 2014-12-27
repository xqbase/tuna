package com.xqbase.tuna.misc;
import java.io.IOException;

import com.xqbase.tuna.ConnectionWrapper;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;

public class TestBlockServer {
	public static void main(String[] args) throws IOException {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			ForwardServer forward = new ForwardServer(connector, "ns2.xqbase.com", 23);
			connector.add(forward.appendFilter(() -> new ConnectionWrapper() {
				@Override
				public void onQueue(int delta, int total) {
					super.onQueue(delta, total);
					System.out.println("Local.onQueue(" + delta + ", " + total + "): " + this);
				}
			}), 23);
			forward.appendRemoteFilter(() -> new ConnectionWrapper() {
				@Override
				public void onQueue(int delta, int total) {
					super.onQueue(delta, total);
					System.out.println("Remote.onQueue(" + delta + ", " + total + "): " + this);
				}
			});
			connector.doEvents();
		}
	}
}