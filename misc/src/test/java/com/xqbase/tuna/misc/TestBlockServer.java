package com.xqbase.tuna.misc;
import java.io.IOException;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectorImpl;

public class TestBlockServer {
	public static void main(String[] args) throws IOException {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			ForwardServer forward = new ForwardServer(connector, "ns2.xqbase.com", 23);
			connector.add(forward.appendFilter(() -> new ConnectionFilter() {
				@Override
				public void onQueue(int size) {
					super.onQueue(size);
					System.out.println("Local.onQueue(" + size + "): " + this);
				}
			}), 23);
			forward.appendRemoteFilter(() -> new ConnectionFilter() {
				@Override
				public void onQueue(int size) {
					super.onQueue(size);
					System.out.println("Remote.onQueue(" + size + "): " + this);
				}
			});
			connector.doEvents();
		}
	}
}