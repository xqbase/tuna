package com.xqbase.net.misc;

import java.io.File;
import java.util.ArrayList;

import com.xqbase.net.Connector;
import com.xqbase.net.FilterFactory;
import com.xqbase.net.ServerConnection;

public class TestFlash {
	private static Connector newConnector() {
		return new Connector();
	}

	public static void main(String[] args) throws Exception {
		// Evade resource leak warning
		Connector connector = newConnector();
		connector.add(new CrossDomainServer(new File(TestFlash.class.
				getResource("/crossdomain.xml").toURI())), 843);
		ServerConnection broadcastServer = connector.add(new BroadcastServer(false), 23);

		ArrayList<FilterFactory> ffs = broadcastServer.getFilterFactories();
		// Application Data Dumped onto System.out
		ffs.add(new DumpFilterFactory().setDumpText(true));
		ffs.add(ZLibFilter::new);
		// Network Data Dumped onto System.err
		ffs.add(new DumpFilterFactory().setDumpStream(System.err));

		DoSFilterFactory dosff = new DoSFilterFactory(60000, 65536, 60, 10);
		connector.getFilterFactories().add(dosff);
		connector.getFilterFactories().add(new IPTrustSet("127.0.0.1"));
		connector.doEvents();
	}
}