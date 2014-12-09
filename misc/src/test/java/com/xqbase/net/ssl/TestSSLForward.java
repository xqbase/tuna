package com.xqbase.net.ssl;

import java.security.KeyStore;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectionWrapper;
import com.xqbase.net.Connector;
import com.xqbase.net.misc.BroadcastServer;
import com.xqbase.net.misc.DumpFilter;
import com.xqbase.net.misc.ForwardServer;
import com.xqbase.net.util.Bytes;

public class TestSSLForward {
	public static void main(String[] args) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(TestSSLForward.class.getResourceAsStream("/localhost.pfx"),
				"changeit".toCharArray());
		CertKey certKey = new CertKey(keyStore, "changeit");
		CertMap certMap = new CertMap();
		certMap.add(TestSSLForward.class.getResourceAsStream("/localhost.cer"));

		try (Connector connector = new Connector()) {
			BroadcastServer broadcastServer = new BroadcastServer(false) {
				@Override
				public Connection get() {
					SSLFilter sslf = new SSLFilter(connector, SSLUtil.
							getSSLContext(certKey, certMap), SSLFilter.SERVER_WANT_AUTH);
					Connection connection = super.get().
							appendFilter(new ConnectionWrapper() {
						@Override
						public void onConnect() {
							super.onConnect();
							System.out.println("ssl_session_id=" +
									Bytes.toHexLower(sslf.getSession().getId()));
						}
					}).appendFilter(sslf);
					return connection;
				}
			};
			connector.add(broadcastServer.appendFilter(() -> new DumpFilter()), 2323);

			ForwardServer forwardServer = new ForwardServer(connector, "localhost", 2323);
			forwardServer.appendRemoteFilter(() -> new SSLFilter(connector,
					SSLUtil.getSSLContext(certKey, certMap), SSLFilter.CLIENT));
			forwardServer.appendRemoteFilter(() -> new DumpFilter().
					setDumpStream(System.err).setClientMode(true));
			connector.add(forwardServer, 2424);

			connector.doEvents();
		}
	}
}