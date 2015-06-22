package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;

public class TestSSLProxy {
	public static void main(String[] args) {
		Log.getAndSet(Conf.openLogger("", 16777216, 10));
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

			ProxyServer server = new ProxyServer(connector, connector, connector);
			server.setLookup(t -> "localhost");
			server.setEnableReverse(true);
			server.setForwardedType(ProxyConnection.FORWARDED_ON);
			server.setLogLevel(ProxyConnection.LOG_DEBUG);
			connector.scheduleDelayed(server.getSchedule(), 10000, 10000);

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