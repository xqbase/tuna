package com.xqbase.tuna.cli;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionWrapper;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.mux.EdgeServer;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

class EdgeLoop implements Runnable {
	private static SSLContext sslc;

	static {
		try {
			sslc = SSLContext.getInstance("TLS");
			sslc.init(SSLManagers.DEFAULT_KEY_MANAGERS,
					SSLManagers.DEFAULT_TRUST_MANAGERS, null);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	MuxContext context;
	ConnectorImpl connector;
	int port;

	private byte[] authPhrase;
	private int originPort;
	private String originHost;
	private boolean ssl;

	EdgeLoop(ConnectorImpl connector, byte[] authPhrase, int queueLimit,
			int port, String originHost, int originPort, boolean ssl) {
		context = new MuxContext(connector, null, queueLimit);
		this.connector = connector;
		this.authPhrase = authPhrase;
		this.port = port;
		this.originHost = originHost;
		this.originPort = originPort;
		this.ssl = ssl;
	}

	@Override
	public void run() {
		EdgeServer server = new EdgeServer(context);
		server.setAuthPhrase(authPhrase);
		Connection connection = new ConnectionWrapper(server.getOriginConnection()) {
			private boolean[] queued = {false};
			private Connector.Closeable closeable = null;
			private String local = "0.0.0.0:0", remote = "0.0.0.0:0";
			private ConnectionHandler handler;

			@Override
			public void setHandler(ConnectionHandler handler) {
				super.setHandler(handler);
				this.handler = handler;
			}

			@Override
			public void onQueue(int size) {
				super.onQueue(size);
				if (context.isQueueChanged(size, queued)) {
					Log.d((size == 0 ? "Origin Connection Unblocked, " :
						"Origin Connection Blocked (" + size + "), ") +
						local + " => " + remote);
				}
			}

			@Override
			public void onConnect(String localAddr, int localPort,
					String remoteAddr, int remotePort) {
				super.onConnect(localAddr, localPort, remoteAddr, remotePort);
				local = localAddr + ":" + localPort;
				remote = remoteAddr + ":" + remotePort;
				Log.i("Origin Connection Established, " + local + " => " + remote);
				try {
					closeable = connector.add(server, port);
				} catch (IOException e) {
					Log.w(e.getMessage());
					Log.i("Disconnect Origin and wait 1 second before retry ...");
					handler.disconnect();
					connector.postDelayed(EdgeLoop.this, Time.SECOND);
				}
			}

			@Override
			public void onDisconnect() {
				super.onDisconnect();
				if (closeable == null) {
					Log.i("Origin Connection Failed, " + local + " => " + remote);
				} else {
					closeable.close();
					closeable = null;
					Log.i("Origin Connection Lost, " + local + " <= " + remote);
				}
				Log.i("Disconnect Origin and wait 1 second before retry ...");
				connector.postDelayed(EdgeLoop.this, Time.SECOND);
			}
		};
		try {
			if (ssl) {
				connection = connection.appendFilter(new SSLFilter(connector,
						connector, sslc, SSLFilter.CLIENT));
			}
			connector.connect(connection, originHost, originPort);
		} catch (IOException e) {
			Log.w("Edge Aborted: " + e.getMessage());
			connector.interrupt();
		}
	}
}

public class Edge {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("Tuna Edge Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.Edge " +
					"<local-port> <origin-host> <origin-port> [-s] [<auth-phrase>]");
			service.shutdown();
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("Edge.", 16777216, 10));

		int port = Numbers.parseInt(args[0], 80, 1, 65535);
		String originHost = args[1];
		int originPort = Numbers.parseInt(args[2], 8341, 1, 65535);
		byte[] authPhrase;
		boolean ssl = args.length >= 4 && "-s".equalsIgnoreCase(args[3]);
		if (ssl) {
			authPhrase = (args.length < 5 || args[4] == null ? null : args[4].getBytes());
		} else {
			authPhrase = (args.length < 4 || args[3] == null ? null : args[3].getBytes());
		}
		Properties p = Conf.load("Edge");
		int queueLimit = Numbers.parseInt(p.getProperty("queue_limit"), 1048576);

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			new EdgeLoop(connector, authPhrase, queueLimit,
					port, originHost, originPort, ssl).run();
			Log.i(String.format("Edge Started (%s->%s:%s)",
					"" + port, originHost, "" + originPort));
			connector.doEvents();
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("Edge Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}