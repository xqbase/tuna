package com.xqbase.tuna.ssl;

import java.io.IOException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.util.Bytes;

public class TestSSLClient {
	static boolean connected = false;

	static void dumpSession(ConnectionSession session) {
		if (!(session instanceof SSLConnectionSession)) {
			return;
		}
		SSLSession ssls = ((SSLConnectionSession) session).getSSLSession();
		System.out.println("ssl_session_id=" + Bytes.toHexLower(ssls.getId()));
		System.out.println("peer_host=" + ssls.getPeerHost());
		System.out.println("peer_port=" + ssls.getPeerPort());
/*
		try {
			System.out.println("peer_certificate=" + ssls.getPeerCertificates()[0]);
		} catch (SSLPeerUnverifiedException e) {
			// Ignored
		}
*/
		System.out.println();
	}

	public static void main(String[] args) throws Exception {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			SSLContext sslc = SSLUtil.getSSLContext(null, null);
			SSLFilter sslf1 = new SSLFilter(connector, connector,
					sslc, SSLFilter.CLIENT, "localhost", 2323);
			SSLFilter sslf2 = new SSLFilter(connector, connector,
					sslc, SSLFilter.CLIENT, "localhost", 2323);
			Connection connection1 = new Connection() {
				@Override
				public void onConnect(ConnectionSession session) {
					dumpSession(session);
					connected = true;
				}
			};
			Connection connection2 = new Connection() {
				@Override
				public void onConnect(ConnectionSession session) {
					dumpSession(session);
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