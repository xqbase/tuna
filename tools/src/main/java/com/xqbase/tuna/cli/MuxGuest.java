package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.mux.GuestServer;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

class GuestLoop implements Runnable {
	private static SSLContext sslc;

	static {
		try {
			sslc = SSLContexts.get(null, 0);
		} catch (IOException | GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	GuestServer guest = null;
	ConnectorImpl connector;
	int publicPort;

	private String privateHost, muxHost;
	private int privatePort, muxPort, queueLimit, logLevel;
	private boolean ssl;
	private byte[] authPhrase;

	GuestLoop(ConnectorImpl connector, int publicPort,
			String privateHost, int privatePort, String muxHost, int muxPort,
			boolean ssl, byte[] authPhrase, int queueLimit, int logLevel) {
		this.connector = connector;
		this.publicPort = publicPort;
		this.privateHost = privateHost;
		this.privatePort = privatePort;
		this.muxHost = muxHost;
		this.muxPort = muxPort;
		this.ssl = ssl;
		this.authPhrase = authPhrase;
		this.queueLimit = queueLimit;
		this.logLevel = logLevel;
	}

	@Override
	public void run() {
		MuxContext context = new MuxContext(connector, t -> {
			Log.w("Listening or Authentication Failed");
			if (guest == null) {
				return false;
			}
			Log.i("Disconnect Mux and wait 1 second before retry ...");
			guest.disconnect();
			guest = null;
			connector.postDelayed(GuestLoop.this, Time.SECOND);
			return false;
		}, queueLimit, logLevel);
		ForwardServer server = new ForwardServer(connector, privateHost, privatePort);
		guest = new GuestServer(server, context, authPhrase, publicPort);
		Connection connection = guest.getMuxConnection().appendFilter(new ConnectionFilter() {
			@Override
			public void onDisconnect() {
				super.onDisconnect();
				disconnect_();
			}

			@Override
			public void disconnect() {
				super.disconnect();
				disconnect_();
			}

			private void disconnect_() {
				if (guest == null) {
					return;
				}
				Log.i("Wait 1 second before retry ...");
				guest = null;
				connector.postDelayed(GuestLoop.this, Time.SECOND);
			}
		});
		try {
			if (ssl) {
				TimeoutQueue<SSLFilter> ssltq = SSLFilter.getTimeoutQueue(60000);
				connector.scheduleDelayed(ssltq, 10000, 10000);
				connection = connection.appendFilter(new SSLFilter(connector,
						connector, ssltq, sslc, SSLFilter.CLIENT));
			}
			connector.connect(connection, muxHost, muxPort);
		} catch (IOException e) {
			Log.w("Guest Aborted: " + e.getMessage());
			connector.interrupt();
		}
	}
}

public class MuxGuest {
	private static final List<String> LOG_VALUE = Arrays.asList("debug", "verbose");

	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 5) {
			System.out.println("MuxGuest Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.MuxGuest <public-port> <private-host> " +
					"<private-port> <mux-host> <mux-port> [-s] [<auth-phrase>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("MuxGuest.", 16777216, 10));

		int publicPort = Numbers.parseInt(args[0], 80, 1, 65535);
		String privateHost = args[1];
		int privatePort = Numbers.parseInt(args[2], 80, 1, 65535);
		String muxHost = args[3];
		int muxPort = Numbers.parseInt(args[4], 8341, 1, 65535);
		boolean ssl = args.length > 5 && "-s".equalsIgnoreCase(args[5]);
		int i = ssl ? 6 : 5;
		byte[] authPhrase = (args.length <= i ||
				args[i] == null ? null : args[i].getBytes());
		Properties p = Conf.load("Mux");
		int queueLimit = Numbers.parseInt(p.getProperty("queue_limit"), 1048576);
		String logValue = Conf.DEBUG ? "verbose" : p.getProperty("log");
		int logLevel = logValue == null ? 0 :
				LOG_VALUE.indexOf(logValue.toLowerCase()) + 1;

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);
			new GuestLoop(connector, publicPort, privateHost, privatePort,
					muxHost, muxPort, ssl, authPhrase, queueLimit, logLevel).run();
			Log.i(String.format("MuxGuest Started (%s:%s->%s:%s) via %s:%s%s",
					muxHost, "" + publicPort, privateHost, "" + privatePort,
					muxHost, "" + muxPort, ssl ? "s" : ""));
			connector.doEvents();
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("MuxHost Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}