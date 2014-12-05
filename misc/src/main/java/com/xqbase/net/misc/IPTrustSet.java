package com.xqbase.net.misc;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.Supplier;

import com.xqbase.net.ConnectionWrapper;

/**
 * A set of trusted IPs. This set also implements the interface
 * "ServerFilter", which can prevent connecting with untrusted IPs.
 */
public class IPTrustSet extends HashSet<String> implements Supplier<ConnectionWrapper> {
	private static final long serialVersionUID = 1L;

	/** Creates an IPTrustSet with the given IPs. */
	public IPTrustSet(String... ips) {
		this(Arrays.asList(ips));
	}

	/** Creates an IPTrustSet with the given collection of IPs. */
	public IPTrustSet(Collection<String> ips) {
		addAll(ips);
	}

	@Override
	public ConnectionWrapper get() {
		return new ConnectionWrapper() {
			@Override
			public void onConnect() {
				System.out.println(getRemoteAddr());
				if (contains(getRemoteAddr())) {
					super.onConnect();
				} else {
					disconnect();
					onDisconnect();
				}
			}
		};
	}
}