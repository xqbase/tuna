package com.xqbase.tuna.tools.ssl;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

public class SSLForward {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("SSLForward Usage: java -cp tuna.jar " +
					"com.xqbase.tuna.tools.ssl.SSLForward " +
					"[<local-host>] <local-port> <remote-host> <remote-port>");
			service.shutdown();
			return;
		}

		String localHost, remoteHost;
		int localPort, remotePort;
		Logger logger = Log.getAndSet(Conf.openLogger("SSLForward.", 16777216, 10));
		if (args.length < 4) {
			localHost = null;
			localPort = Numbers.parseInt(args[0], 443, 1, 65535);
			remoteHost = args[1];
			remotePort = Numbers.parseInt(args[2], 443, 1, 65535);
			Log.i(String.format("SSLForward Started in Client mode (%s->%s:%s)",
					"" + localPort, remoteHost, "" + remotePort));
		} else {
			localHost = args[0];
			localPort = Numbers.parseInt(args[1], 443, 1, 65535);
			remoteHost = args[2];
			remotePort = Numbers.parseInt(args[3], 443, 1, 65535);
			Log.i(String.format("SSLForward Started in Server mode (%s:%s->%s:%s)",
					localHost, "" + localPort, remoteHost, "" + remotePort));
		}
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			ForwardServer server = new ForwardServer(connector, remoteHost, remotePort);
			if (localHost == null) {
				SSLContext sslc = SSLContexts.get(null);
				server.appendRemoteFilter(() ->
						new SSLFilter(connector, sslc, SSLFilter.CLIENT));
				connector.add(server, localPort);
			} else {
				SSLContext sslc = SSLContexts.get("CN=" + localHost);
				connector.add(server.appendFilter(() ->
						new SSLFilter(connector, sslc, SSLFilter.SERVER_NO_AUTH)), localPort);
			}
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("SSLForward Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}