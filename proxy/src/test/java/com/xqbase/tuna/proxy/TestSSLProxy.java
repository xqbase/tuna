package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.util.Log;

public class TestSSLProxy {
	public static void main(String[] args) {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			ks.load(TestSSLProxy.class.getResourceAsStream("/localhost.pfx"),
					"changeit".toCharArray());
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, "changeit".toCharArray());
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
			tmf.init(ks);
			SSLContext sslcServer = SSLContext.getInstance("TLS");
			sslcServer.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
			
			SSLContext sslcClient = SSLContext.getInstance("TLS");
			sslcClient.init(SSLManagers.DEFAULT_KEY_MANAGERS,
					SSLManagers.DEFAULT_TRUST_MANAGERS, null);

			ProxyContext context = new ProxyContext(connector, connector, connector,
					sslcClient, (t) -> "localhost", (t, u) -> true, null, true,
					ProxyContext.FORWARDED_ON, ProxyContext.LOG_DEBUG); 
			ServerConnection server = () -> new ProxyConnection(context);
			connector.add(server.appendFilter(() -> new SSLFilter(connector,
					connector, sslcServer, SSLFilter.SERVER_WANT_AUTH)), 8443);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}
	}
}