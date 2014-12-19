package com.xqbase.net.misc;

import java.io.File;

import com.xqbase.net.ConnectorImpl;

public class TestFlash {
	public static void main(String[] args) throws Exception {
		DoSServerFilter dossf = new DoSServerFilter(60000, 10, 60, 65536);
		IPTrustSet ipts = new IPTrustSet("127.0.0.1", "0:0:0:0:0:0:0:1");
		// Evade resource leak warning
		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(new CrossDomainServer(new File(TestFlash.class.
					getResource("/crossdomain.xml").toURI())).
					appendFilter(dossf).appendFilter(ipts), 843);
			// Application Data Dumped onto System.out
			// Network Data Dumped onto System.err
			connector.add(new BroadcastServer(false).
					appendFilter(() -> new DumpFilter().setDumpText(true)).
					appendFilter(ZLibFilter::new).
					appendFilter(() -> new DumpFilter().setDumpStream(System.err)).
					appendFilter(dossf).appendFilter(ipts), 23);
			connector.doEvents();
		}
	}
}