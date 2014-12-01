package com.xqbase.net.ssl;

import java.security.KeyStore;

import com.xqbase.net.Connector;
import com.xqbase.net.ServerConnection;
import com.xqbase.net.misc.BroadcastServer;
import com.xqbase.net.misc.DumpFilterFactory;
import com.xqbase.net.misc.ForwardServer;

public class TestSSLForward {
	public static void main(String[] args) throws Exception {
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(TestSSLForward.class.getResourceAsStream("/localhost.pfx"),
				"changeit".toCharArray());
		CertKey certKey = new CertKey(keyStore, "changeit");
		CertMap certMap = new CertMap();
		certMap.add(TestSSLForward.class.getResourceAsStream("/localhost.cer"));

		Connector connector = new Connector();
		ServerConnection broadcastServer = connector.add(new BroadcastServer(), 2323);
		SSLFilterFactory sslffServer = new SSLFilterFactory(SSLUtil.
				getSSLContext(certKey, certMap), SSLFilter.SERVER_WANT_AUTH);
		broadcastServer.getFilterFactories().add(sslffServer);
		broadcastServer.getFilterFactories().add(new DumpFilterFactory());

		ForwardServer forwardServer = new ForwardServer(connector, "localhost", 2323);
		SSLFilterFactory sslffClient = new SSLFilterFactory(SSLUtil.
				getSSLContext(certKey, certMap), SSLFilter.CLIENT);
		forwardServer.getFilterFactories().add(sslffClient);
		forwardServer.getFilterFactories().add(new DumpFilterFactory().
				setDumpStream(System.err).setUseClientMode(true));
		connector.add(forwardServer, 2424);

		connector.doEvents();
	}
}