package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

public class SSLForward {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("SSLForward Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.SSLForward " +
					"[<local-host>] <local-port> <remote-host> <remote-port>");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("SSLForward.", 16777216, 10));

		String localHost, remoteHost;
		int localPort, remotePort;
		if (args.length < 4) {
			localHost = null;
			localPort = Numbers.parseInt(args[0], 443, 1, 65535);
			remoteHost = args[1];
			remotePort = Numbers.parseInt(args[2], 443, 1, 65535);
		} else {
			localHost = args[0];
			localPort = Numbers.parseInt(args[1], 443, 1, 65535);
			remoteHost = args[2];
			remotePort = Numbers.parseInt(args[3], 443, 1, 65535);
		}
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);

			TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(60000);
			connector.scheduleDelayed(ssltq, 10000, 10000);
			ForwardServer server = new ForwardServer(connector, remoteHost, remotePort);
			if (localHost == null) {
				SSLContext sslc = SSLContexts.get(null, 0);
				server.appendRemoteFilter(() -> new SSLFilter(connector,
						connector, ssltq, sslc, SSLFilter.CLIENT));
				connector.add(server, localPort);
				Log.i(String.format("SSLForward Started in Client mode (%s->%s:%s)",
						"" + localPort, remoteHost, "" + remotePort));
			} else {
				SSLContext sslc = SSLContexts.get("CN=" + localHost, Time.WEEK * 520);
				connector.add(server.appendFilter(() -> new SSLFilter(connector,
						connector, ssltq, sslc, SSLFilter.SERVER_NO_AUTH)), localPort);
				Log.i(String.format("SSLForward Started in Server mode (%s:%s->%s:%s)",
						localHost, "" + localPort, remoteHost, "" + remotePort));
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