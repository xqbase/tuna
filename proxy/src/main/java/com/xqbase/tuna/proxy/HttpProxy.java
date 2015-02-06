package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.util.SSLManagers;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

public class HttpProxy {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		Logger logger = Log.getAndSet(Conf.openLogger("HttpProxy.", 16777216, 10));
		Properties p = Conf.load("HttpProxy");
		String host = p.getProperty("host");
		host = host == null || host.isEmpty() ? "0.0.0.0" : host;
		int port = Numbers.parseInt(p.getProperty("port"), 3128, 1, 65535);
		String username = p.getProperty("username");
		BiPredicate<String, String> auth;
		if (username == null) {
			auth = null;
		} else {
			String password = p.getProperty("password");
			auth = (t, u) -> username.equals(t) && (password == null || password.equals(u));
		}

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			SSLContext sslc = SSLContext.getInstance("TLS");
			sslc.init(SSLManagers.DEFAULT_KEY_MANAGERS, SSLManagers.DEFAULT_TRUST_MANAGERS, null);
			connector.add(() -> new ProxyConnection(connector, connector, sslc, auth), host, port);
			Log.i("HTTP Proxy Started on " + host + ":" + port);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("HTTP Proxy Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}