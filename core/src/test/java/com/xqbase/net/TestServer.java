package com.xqbase.net;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TestServer {
	static AtomicInteger accepts = new AtomicInteger(0),
			requests = new AtomicInteger(0), errors = new AtomicInteger(0);

	private static Connector newConnector() {
		return new Connector();
	}

	public static void main(String[] args) throws IOException {
		// Evade resource leak warning
		Connector connector = newConnector();
		connector.add(new ServerConnection(2626) {
			@Override
			protected Connection createConnection() {
				return new Connection() {
					@Override
					protected void onRecv(byte[] b, int off, int len) {
						send(b, off, len);
						requests.incrementAndGet();
					}

					@Override
					protected void onConnect() {
						accepts.incrementAndGet();
					}

					@Override
					protected void onDisconnect(boolean active) {
						errors.incrementAndGet();
					}
				};
			}
		});
		ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
		long startTime = System.currentTimeMillis();
		timer.scheduleAtFixedRate(() -> {
			System.out.print("Time: " +
					(System.currentTimeMillis() - startTime) + ", ");
			System.out.print("Accepts: " + accepts + ", ");
			System.out.print("Errors: " + errors + ", ");
			System.out.println("Requests: " + requests);
			if (accepts.get() > 10000) {
				connector.interrupt();
			}
		}, 0, 1000, TimeUnit.MILLISECONDS);
		connector.doEvents();
		timer.shutdown();
	}
}