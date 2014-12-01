package com.xqbase.net.ssl;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import com.xqbase.net.Connection;
import com.xqbase.net.Connector;
import com.xqbase.net.Listener;
import com.xqbase.util.Bytes;

public class TestSSLClient {
	static boolean connected = false;

	public static void main(String[] args) throws Exception {
		ExecutorService executor = Executors.newCachedThreadPool();
		try (Connector connector = new Connector()) {
			SSLContext sslc = SSLUtil.getSSLContext(null, null);
			SSLFilter sslf1 = new SSLFilter(executor, sslc,
					SSLFilter.CLIENT, "localhost", 2323);
			SSLFilter sslf2 = new SSLFilter(executor, sslc,
					SSLFilter.CLIENT, "localhost", 2323);
			Listener listener1 = new Listener() {
				@Override
				public void onConnect() {
					System.out.println(Bytes.toHexLower(sslf1.getSession().getId()));
					connected = true;
				}
			};
			Listener listener2 = new Listener() {
				@Override
				public void onConnect() {
					System.out.println(Bytes.toHexLower(sslf2.getSession().getId()));
					try {
						connector.connect(listener1, "localhost", 2323).appendFilter(sslf1);
					} catch (IOException e) {/**/}
				}
			};
			Connection connection2 = connector.connect(listener2, "localhost", 2323);
			connection2.appendFilter(sslf2);
			while (!connected) {
				connector.doEvents(-1);
			}
		}
		executor.shutdown();
	}
}