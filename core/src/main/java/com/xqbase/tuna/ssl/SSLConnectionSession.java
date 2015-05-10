package com.xqbase.tuna.ssl;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLSession;

import com.xqbase.tuna.ConnectionSession;

public class SSLConnectionSession extends ConnectionSession {
	private SSLSession sslSession;

	public SSLConnectionSession(InetSocketAddress localSocketAddress,
			InetSocketAddress remoteSocketAddress, SSLSession sslSession) {
		super(localSocketAddress, remoteSocketAddress);
		this.sslSession = sslSession;
	}

	public SSLSession getSSLSession() {
		return sslSession;
	}
}