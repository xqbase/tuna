package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

public class Forward {
	private static Service service = new Service();

	private static boolean option(String[] args, int position, String expected) {
		return args.length > position && expected.equalsIgnoreCase(args[position]);
	}

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("Forward Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.Forward <local-port> " +
					"<remote-host> <remote-port> [-s] [-d]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("Forward.", 16777216, 10));

		int port = Numbers.parseInt(args[0], 443, 1, 65535);
		String remoteHost = args[1];
		int remotePort = Numbers.parseInt(args[2], 443, 1, 65535);
		boolean ssl = option(args, 3, "-s");
		boolean dump = ssl ? option(args, 4, "-d") : option(args, 3, "-d");
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			ForwardServer server = new ForwardServer(connector, remoteHost, remotePort);
			if (ssl) {
				TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(10000);
				connector.scheduleDelayed(ssltq, 1000, 1000);
				SSLContext sslc = SSLContexts.get(null, 0);
				server.appendRemoteFilter(() -> new SSLFilter(connector,
						connector, ssltq, sslc, SSLFilter.CLIENT));
			}
			ServerConnection server_ = dump ?
					server.appendFilter(() -> new DumpFilter().setDumpText(true)) :
					server;
			connector.add(server_, port);
			Log.i(String.format("Forward Started (%s->%s:%s%s)",
					"" + port, remoteHost, "" + remotePort, ssl ? "s" : ""));
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("Forward Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}