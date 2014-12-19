package com.xqbase.net.ssl;

import java.io.IOException;

import javax.net.ssl.SSLContext;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectorImpl;
import com.xqbase.net.util.Bytes;

public class TestSSLClient {
	static boolean connected = false;

	public static void main(String[] args) throws Exception {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			SSLContext sslc = SSLUtil.getSSLContext(null, null);
			SSLFilter sslf1 = new SSLFilter(connector, sslc,
					SSLFilter.CLIENT, "localhost", 2323);
			SSLFilter sslf2 = new SSLFilter(connector, sslc,
					SSLFilter.CLIENT, "localhost", 2323);
			Connection connection1 = new Connection() {
				@Override
				public void onConnect() {
					System.out.println(Bytes.toHexLower(sslf1.getSession().getId()));
					connected = true;
				}
			};
			Connection connection2 = new Connection() {
				@Override
				public void onConnect() {
					System.out.println(Bytes.toHexLower(sslf2.getSession().getId()));
					try {
						connector.connect(connection1.appendFilter(sslf1), "localhost", 2323);
					} catch (IOException e) {/**/}
				}
			};
			connector.connect(connection2.appendFilter(sslf2), "localhost", 2323);
			while (!connected) {
				connector.doEvents(-1);
			}
		}
	}
}