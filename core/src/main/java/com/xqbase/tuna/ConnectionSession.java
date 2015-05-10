package com.xqbase.tuna;

import java.net.InetSocketAddress;

public class ConnectionSession {
	private InetSocketAddress localSocketAddress, remoteSocketAddress;

	public ConnectionSession(InetSocketAddress localSocketAddress,
			InetSocketAddress remoteSocketAddress) {
		this.localSocketAddress = localSocketAddress;
		this.remoteSocketAddress = remoteSocketAddress;
	}

	public InetSocketAddress getLocalSocketAddress() {
		return localSocketAddress;
	}

	public InetSocketAddress getRemoteSocketAddress() {
		return remoteSocketAddress;
	}

	public String getLocalAddr() {
		return localSocketAddress.getAddress().getHostAddress();
	}

	public int getLocalPort() {
		return localSocketAddress.getPort();
	}

	public String getRemoteAddr() {
		return remoteSocketAddress.getAddress().getHostAddress();
	}

	public int getRemotePort() {
		return remoteSocketAddress.getPort();
	}
}