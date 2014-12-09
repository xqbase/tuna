package com.xqbase.net;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class TestClient {
	static AtomicInteger connections = new AtomicInteger(0),
			responses = new AtomicInteger(0), errors = new AtomicInteger(0);

	private static Connector newConnector() {
		return new Connector();
	}

	// To test under Windows, you should add a registry item "MaxUserPort = 65534" in
	// HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters
	public static void main(String[] args) throws Exception {
		final LinkedHashSet<ConnectionHandler> handlerSet = new LinkedHashSet<>();
		Random random = new Random();
		// Evade resource leak warning
		Connector connector = newConnector();
		int count = 0;
		long startTime = System.currentTimeMillis();
		byte[] data = new byte[] {'-'};
		while (true) {
			for (ConnectionHandler handler : handlerSet.toArray(new ConnectionHandler[0])) {
				// "conn.onDisconnect()" might change "connectionSet"
				if (random.nextDouble() < .01) {
					handler.send(data);
				}
			}
			// Increase connection every 16 milliseconds
			connector.doEvents(16);
			count ++;
			if (count == 100) {
				count = 0;
				System.out.print("Time: " +
						(System.currentTimeMillis() - startTime) + ", ");
				System.out.print("Connections: " + connections + ", ");
				System.out.print("Errors: " + errors + ", ");
				System.out.println("Responses: " + responses);
			}
			connector.connect(new Connection() {
				private boolean connected = false;
				private ConnectionHandler handler;

				@Override
				public void setHandler(ConnectionHandler handler) {
					this.handler = handler;
				}

				@Override
				public void onRecv(byte[] b, int off, int len) {
					responses.incrementAndGet();
				}

				@Override
				public void onConnect() {
					connected = true;
					handlerSet.add(handler);
					connections.incrementAndGet();
				}

				@Override
				public void onDisconnect() {
					if (connected) {
						handlerSet.remove(handler);
						errors.incrementAndGet();
					}
				}
			}, "localhost", 2626);
		}
	}
}