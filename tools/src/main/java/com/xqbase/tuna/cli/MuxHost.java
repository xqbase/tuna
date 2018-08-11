package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.mux.HostServer;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

public class MuxHost {
	private static final List<String> LOG_VALUE = Arrays.asList("debug", "verbose");

	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 1) {
			System.out.println("MuxHost Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.MuxHost <port> [-s] [<auth-phrase>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("MuxHost.", 16777216, 10));

		int port = Numbers.parseInt(args[0], 8341, 1, 65535);
		boolean ssl = args.length > 1 && "-s".equalsIgnoreCase(args[1]);
		int i = ssl ? 2 : 1;
		byte[] authPhrase = (args.length <= i ||
				args[i] == null ? null : args[i].getBytes());
		Predicate<byte[]> auth = authPhrase == null ? t -> true :
				t -> t != null && Bytes.equals(t, authPhrase);
		Properties p = Conf.load("Mux");
		int queueLimit = Numbers.parseInt(p.getProperty("queue_limit"), 1048576);
		String logValue = Conf.DEBUG ? "verbose" : p.getProperty("log");
		int logLevel = logValue == null ? 0 :
				LOG_VALUE.indexOf(logValue.toLowerCase()) + 1;

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);
			ServerConnection server = new HostServer(connector,
					new MuxContext(connector, auth, queueLimit, logLevel));
			if (ssl) {
				TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(60000);
				connector.scheduleDelayed(ssltq, 10000, 10000);
				SSLContext sslc = SSLContexts.get("CN=localhost", Time.WEEK * 520);
				server = server.appendFilter(() -> new SSLFilter(connector,
						connector, ssltq, sslc, SSLFilter.SERVER_NO_AUTH));
			}
			connector.add(server, port);
			Log.i("MuxHost Started on " + (ssl ? "SSL Port " : "Port ") + port);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("MuxHost Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}