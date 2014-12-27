package com.xqbase.tuna.tools.ssl;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

public class SSLDump {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 2) {
			System.out.println("SSLDump Usage: java -cp tuna.jar " +
					"com.xqbase.tuna.tools.ssl.SSLDump <host-name> <host-addr> [<port>]");
			service.shutdown();
			return;
		}

		String hostName = args[0];
		String hostAddr = args[1];
		int port = args.length < 3 ? 443 : Numbers.parseInt(args[2], 443);
		Logger logger = Log.getAndSet(Conf.openLogger("SSLDump.", 16777216, 10));
		Log.i(String.format("SSLDump Started (%s:%s->%s:%s)",
				hostName, "" + port, hostAddr, "" + port));
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			SSLContext sslcServer = SSLUtil.getSSLContext("CN=" + hostName);
			SSLContext sslcClient = SSLUtil.getSSLContext(null);
			ForwardServer server = new ForwardServer(connector, hostAddr, port);
			server.appendRemoteFilter(() -> new SSLFilter(connector,
					sslcClient, SSLFilter.CLIENT));
			connector.add(server.appendFilter(() -> new DumpFilter().setDumpText(true)).
					appendFilter(() -> new SSLFilter(connector,
					sslcServer, SSLFilter.SERVER_NO_AUTH)), 443);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("SSLDump Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}