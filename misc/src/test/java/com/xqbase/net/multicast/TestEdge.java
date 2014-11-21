package com.xqbase.net.multicast;

import java.util.Properties;

import com.xqbase.net.Connector;
import com.xqbase.net.misc.DumpFilterFactory;

public class TestEdge {
	private static Connector newConnector() {
		return new Connector();
	}

	public static void main(String[] args) throws Exception {
		Properties p = new Properties();
		p.load(TestEdge.class.getResourceAsStream("/Edge.properties"));
		int clientPort = Integer.parseInt(p.getProperty("client_port"));
		String originHost = p.getProperty("origin_host");
		int originPort = Integer.parseInt(p.getProperty("origin_port"));
		boolean dump = Boolean.parseBoolean(p.getProperty("dump"));
		// Evade resource leak warning
		Connector connector = newConnector();
		EdgeServer edge = new EdgeServer(clientPort);
		connector.add(edge);
		connector.connect(edge.getOriginConnection(), originHost, originPort);
		if (dump) {
			edge.getFilterFactories().add(new DumpFilterFactory());
		}
		while (edge.getOriginConnection().isOpen()) {
			connector.doEvents(1000);
		}
	}
}