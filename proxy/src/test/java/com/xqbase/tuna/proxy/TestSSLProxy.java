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
			SSLContext sslc = SSLContext.getInstance("TLS");
			sslc.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

			ProxyServer context = new ProxyServer(connector, connector, connector);
			context.setLookup(t -> "localhost");
			context.setEnableReverse(true);
			context.setForwardedType(ProxyConnection.FORWARDED_ON);
			context.setLogLevel(ProxyConnection.LOG_DEBUG);
			ServerConnection server = () -> new ProxyConnection(context);
			connector.add(server.appendFilter(() -> new SSLFilter(connector,
					connector, sslc, SSLFilter.SERVER_WANT_AUTH)), 8443);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}
	}
}