package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

public class SSLDump {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 2) {
			System.out.println("SSLDump Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.SSLDump <host-name> <host-addr> [<port>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("SSLDump.", 16777216, 10));

		String hostName = args[0];
		String hostAddr = args[1];
		int port = args.length < 3 ? 443 : Numbers.parseInt(args[2], 443, 1, 65535);
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);

			TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(60000);
			connector.scheduleDelayed(ssltq, 10000, 10000);
			SSLContext sslcServer = SSLContexts.get("CN=" + hostName, Time.WEEK * 520);
			SSLContext sslcClient = SSLContexts.get(null, 0);
			ForwardServer server = new ForwardServer(connector, hostAddr, port);
			server.appendRemoteFilter(() -> new SSLFilter(connector,
					connector, ssltq, sslcClient, SSLFilter.CLIENT));
			connector.add(server.appendFilter(() -> new DumpFilter().setDumpText(true)).
					appendFilter(() -> new SSLFilter(connector,
					connector, ssltq, sslcServer, SSLFilter.SERVER_NO_AUTH)), 443);
			Log.i(String.format("SSLDump Started (%s:%s->%s:%s)",
					hostName, "" + port, hostAddr, "" + port));
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