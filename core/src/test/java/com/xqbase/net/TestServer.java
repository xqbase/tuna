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
					requests.incrementAndGet();
				}

				@Override
				public void onConnect() {
					accepts.incrementAndGet();
				}

				@Override
				public void onDisconnect() {
					errors.incrementAndGet();
				}
			};
		}, 2626);
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