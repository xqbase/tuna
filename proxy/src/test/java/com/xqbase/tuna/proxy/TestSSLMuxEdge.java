package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.mux.EdgeServer;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.mux.OriginServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;

public class TestSSLMuxEdge {
	public static void main(String[] args) {
		Log.getAndSet(Conf.openLogger("", 16777216, 10));
		try (ConnectorImpl connector = new ConnectorImpl()) {
			KeyStore ks = KeyStore.getInstance("PKCS12");
			ks.load(TestSSLMuxEdge.class.getResourceAsStream("/localhost.pfx"),
					"changeit".toCharArray());
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, "changeit".toCharArray());
			TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
			tmf.init(ks);
			SSLContext sslc = SSLContext.getInstance("TLS");
			sslc.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

			ProxyContext context = new ProxyContext(connector, connector, connector);
			context.setLookup(t -> "localhost");
			context.setEnableReverse(true);
			context.setForwardedType(ProxyContext.FORWARDED_ON);
			context.setLogLevel(ProxyContext.LOG_DEBUG);
			ServerConnection server = () -> new ProxyConnection(context);

			MuxContext mc = new MuxContext(connector, t -> true, 1048576, MuxContext.LOG_VERBOSE);
			connector.add(new OriginServer(server, mc), 8341);
			EdgeServer edge = new EdgeServer(mc, null);
			connector.add(edge.appendFilter(() -> new SSLFilter(connector,
					connector, sslc, SSLFilter.SERVER_NO_AUTH)), 8443);
			connector.connect(edge.getMuxConnection(), "localhost", 8341);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}
	}
}