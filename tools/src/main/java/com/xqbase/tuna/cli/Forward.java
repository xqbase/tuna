package com.xqbase.tuna.cli;

import java.io.IOException;
import java.util.logging.Logger;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;

public class Forward {
	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 3) {
			System.out.println("Forward Usage: java -cp tuna-tools.jar " +
					"com.xqbase.tuna.cli.Forward " +
					"<local-port> <remote-host> <remote-port>");
			service.shutdown();
			return;
		}

		Logger logger = Log.getAndSet(Conf.openLogger("Forward.", 16777216, 10));
		int port = Numbers.parseInt(args[0], 443, 1, 65535);
		String remoteHost = args[1];
		int remotePort = Numbers.parseInt(args[2], 443, 1, 65535);
		Log.i(String.format("Forward Started (%s->%s:%s)",
				"" + port, remoteHost, "" + remotePort));
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			ForwardServer server = new ForwardServer(connector, remoteHost, remotePort);
			connector.add(server, port);
			connector.doEvents();
		} catch (IOException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("Forward Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}