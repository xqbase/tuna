package com.xqbase.net;

import java.io.IOException;

public class TestServer {
	static int accepts = 0, requests = 0, errors = 0;

	public static void main(String[] args) throws IOException {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(() -> {
				return new Connection() {
					private ConnectionHandler handler;

					@Override
					public void setHandler(ConnectionHandler handler) {
						this.handler = handler;
					}

					@Override
					public void onRecv(byte[] b, int off, int len) {
						handler.send(b, off, len);
						requests ++;
					}

					@Override
					public void onConnect() {
						accepts ++;
					}

					@Override
					public void onDisconnect() {
						errors ++;
					}
				};
			}, 2626);
			long startTime = System.currentTimeMillis();
			TimerHandler.Closeable[] closeable = new TimerHandler.Closeable[] {null};
			closeable[0] = connector.scheduleDelayed(() -> {
				System.out.print("Time: " +
						(System.currentTimeMillis() - startTime) + ", ");
				System.out.print("Accepts: " + accepts + ", ");
				System.out.print("Errors: " + errors + ", ");
				System.out.println("Requests: " + requests);
				if (accepts > 5000) {
					closeable[0].close();
					connector.postDelayed(connector::interrupt, 10000);
				}
			}, 0, 1000);
			connector.doEvents();
		}
	}
}