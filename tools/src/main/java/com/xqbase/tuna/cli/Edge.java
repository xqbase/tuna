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

public class Edge {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("Edge Usage: java -cp tuna-tools.jar " +
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
			Runnable[] retry = new Runnable[1];

			EdgeServer server = new EdgeServer(new MuxContext(connector, null, queueLimit));
			server.setAuthPhrase(authPhrase);
			Connection connection = new ConnectionWrapper(server.getOriginConnection()) {
				private ConnectionHandler handler;
				private Connector.Closeable closeable = null;
				private String local = "0.0.0.0:0", remote = "0.0.0.0:0";

				@Override
				public void setHandler(ConnectionHandler handler) {
					super.setHandler(handler);
					this.handler = handler;
				}

				@Override
				public void onConnect(String localAddr, int localPort,
						String remoteAddr, int remotePort) {
					local = localAddr + ":" + localPort;
					remote = remoteAddr + ":" + remotePort;
					Log.i("Origin Connection Established, " +
							local + " => " + remote);
					try {
						closeable = connector.add(server, port);
					} catch (IOException e) {
						Log.w(e.getMessage());
						Log.i("Disconnect Origin and wait 1 second before retry ...");
						handler.disconnect();
						connector.postDelayed(retry[0], Time.SECOND);
					}
				}

				@Override
				public void onDisconnect() {
					if (closeable == null) {
						Log.i("Origin Connection Failed, " +
								local + " => " + remote);
					} else {
						Log.i("Origin Connection Lost, " +
								local + " <= " + remote);
						closeable.close();
						closeable = null;
					}
					Log.i("Disconnect Origin and wait 1 second before retry ...");
					connector.postDelayed(retry[0], Time.SECOND);
				}
			};
			if (ssl) {
				SSLContext sslc = SSLContext.getInstance("TLS");
				sslc.init(SSLManagers.DEFAULT_KEY_MANAGERS,
						SSLManagers.DEFAULT_TRUST_MANAGERS, null);
				connection = connection.appendFilter(new SSLFilter(connector,
						connector, sslc, SSLFilter.CLIENT));
			}
			Connection connection_ = connection;
			retry[0] = () -> {
				try {
					connector.connect(connection_, originHost, originPort);
				} catch (IOException e) {
					Log.w(e.getMessage());
					Log.i("Aborted");
					connector.interrupt();
				}
			};
			retry[0].run();

			Log.i(String.format("Edge Started (%s->%s:%s)",
					"" + port, originHost, "" + originPort));
			connector.doEvents();
		} catch (Error | RuntimeException | GeneralSecurityException e) {
			Log.e(e);
		}

		Log.i("Forward Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}
