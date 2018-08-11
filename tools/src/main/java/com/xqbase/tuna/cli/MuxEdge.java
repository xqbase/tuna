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
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.mux.EdgeServer;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.util.TimeoutQueue;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

class EdgeLoop implements Runnable {
	private static SSLContext sslc;

	static {
		try {
			sslc = SSLContexts.get(null, 0);
		} catch (IOException | GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	Connector.Closeable closeable = null;
	EdgeServer edge = null;
	ConnectorImpl connector;
	int port;

	private String muxHost;
	private int muxPort, queueLimit, logLevel;
	private boolean ssl;
	private byte[] authPhrase;

	EdgeLoop(ConnectorImpl connector, int port, String muxHost, int muxPort,
			boolean ssl, byte[] authPhrase, int queueLimit, int logLevel) {
		this.connector = connector;
		this.port = port;
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
			Log.w("Authentication Failed");
			if (edge == null) {
				return false;
			}
			Log.i("Disconnect Mux and wait 1 second before retry ...");
			if (closeable != null) {
				closeable.close();
				closeable = null;
			}
			edge.disconnect();
			edge = null;
			connector.postDelayed(EdgeLoop.this, Time.SECOND);
			return false;
		}, queueLimit, logLevel);
		edge = new EdgeServer(context, authPhrase);
		Connection connection = edge.getMuxConnection().appendFilter(new ConnectionFilter() {
			@Override
			public void onConnect(ConnectionSession session) {
				super.onConnect(session);
				try {
					closeable = connector.add(edge, port);
				} catch (IOException e) {
					Log.w(e.getMessage());
					if (edge == null) {
						return;
					}
					Log.i("Disconnect Mux and wait 1 second before retry ...");
					edge.disconnect();
					edge = null;
					connector.postDelayed(EdgeLoop.this, Time.SECOND);
				}
			}

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
				if (closeable != null) {
					closeable.close();
					closeable = null;
				}
				if (edge == null) {
					return;
				}
				Log.i("Wait 1 second before retry ...");
				edge = null;
				connector.postDelayed(EdgeLoop.this, Time.SECOND);
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
			Log.w("Edge Aborted: " + e.getMessage());
			connector.interrupt();
		}
	}
}

public class MuxEdge {
	private static final List<String> LOG_VALUE = Arrays.asList("debug", "verbose");

	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("MuxEdge Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.MuxEdge <local-port> " +
					"<mux-host> <mux-port> [-s] [<auth-phrase>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("MuxEdge.", 16777216, 10));

		int port = Numbers.parseInt(args[0], 80, 1, 65535);
		String muxHost = args[1];
		int muxPort = Numbers.parseInt(args[2], 8341, 1, 65535);
		boolean ssl = args.length > 3 && "-s".equalsIgnoreCase(args[3]);
		int i = ssl ? 4 : 3;
		byte[] authPhrase = (args.length <= i ||
				args[i] == null ? null : args[i].getBytes());
		Properties p = Conf.load("Mux");
		int queueLimit = Numbers.parseInt(p.getProperty("queue_limit"), 1048576);
		String logValue = Conf.DEBUG ? "verbose" : p.getProperty("log");
		int logLevel = logValue == null ? 0 :
				LOG_VALUE.indexOf(logValue.toLowerCase()) + 1;

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);
			new EdgeLoop(connector, port, muxHost, muxPort,
					ssl, authPhrase, queueLimit, logLevel).run();
			Log.i(String.format("MuxEdge Started (%s->%s:%s%s)",
					"" + port, muxHost, "" + muxPort, ssl ? "s" : ""));
			connector.doEvents();
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("Edge Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}