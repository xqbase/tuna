package com.xqbase.net.ssl;

import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xqbase.net.Connector;
import com.xqbase.net.misc.BroadcastServer;
import com.xqbase.net.misc.DumpServerFilter;
import com.xqbase.net.misc.ForwardServer;

public class TestSSLForward {
	public static void main(String[] args) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(TestSSLForward.class.getResourceAsStream("/localhost.pfx"),
				"changeit".toCharArray());
		CertKey certKey = new CertKey(keyStore, "changeit");
		CertMap certMap = new CertMap();
		certMap.add(TestSSLForward.class.getResourceAsStream("/localhost.cer"));

		ExecutorService executor = Executors.newCachedThreadPool();
		try (
			Connector connector = new Connector();
		) {
			BroadcastServer broadcastServer = new BroadcastServer(false);
			SSLServerFilter sslffServer = new SSLServerFilter(executor, SSLUtil.
					getSSLContext(certKey, certMap), SSLFilter.SERVER_WANT_AUTH);
			SSLServerFilter sslffClient = new SSLServerFilter(executor, SSLUtil.
					getSSLContext(certKey, certMap), SSLFilter.CLIENT);
			connector.add(broadcastServer.appendFilter(sslffServer).
					appendFilter(new DumpServerFilter()), 2323);

			ForwardServer forwardServer = new ForwardServer(connector, "localhost", 2323);
			forwardServer.appendRemoteFilter(sslffClient);
			forwardServer.appendRemoteFilter(new DumpServerFilter().
					setDumpStream(System.err).setUseClientMode(true));
			connector.add(forwardServer, 2424);

			connector.doEvents();
		}
		executor.shutdown();
	}
}