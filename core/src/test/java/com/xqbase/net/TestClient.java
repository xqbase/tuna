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
		final LinkedHashSet<Connection> connectionSet = new LinkedHashSet<>();
		Random random = new Random();
		// Evade resource leak warning
		Connector connector = newConnector();
		int count = 0;
		long startTime = System.currentTimeMillis();
		byte[] data = new byte[] {'-'};
		while (true) {
			for (Connection conn : connectionSet.toArray(new Connection[0])) {
				// "conn.onDisconnect()" might change "connectionSet"
				if (random.nextDouble() < .01) {
					conn.send(data);
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
				@Override
				protected void onRecv(byte[] b, int off, int len) {
					responses.incrementAndGet();
				}

				@Override
				protected void onConnect() {
					connectionSet.add(this);
					connections.incrementAndGet();
				}

				@Override
				protected void onDisconnect(boolean active) {
					connectionSet.remove(this);
					errors.incrementAndGet();
				}
			}, "localhost", 2626);
		}
	}
}