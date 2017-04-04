package com.xqbase.tuna;

import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

public class TestClient {
	static int connections = 0, responses = 0, errors = 0;

	// To test under Windows, you should add a registry item "MaxUserPort = 65534" in
	// HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters
	public static void main(String[] args) throws Exception {
		final Set<ConnectionHandler> handlerSet = new LinkedHashSet<>();
		Random random = new Random();
		try (ConnectorImpl connector = new ConnectorImpl()) {
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
						responses ++;
					}

					@Override
					public void onConnect(ConnectionSession session) {
						connected = true;
						handlerSet.add(handler);
						connections ++;
					}

					@Override
					public void onDisconnect() {
						if (connected) {
							handlerSet.remove(handler);
							errors ++;
						}
					}
				}, "localhost", 2626);
			}
		}
	}
}