package com.xqbase.tuna.misc;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.function.Supplier;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionSession;

/**
 * A set of trusted IPs. This set also implements the interface
 * "ServerFilter", which can prevent connecting with untrusted IPs.
 */
public class IPTrustSet extends HashSet<String> implements Supplier<ConnectionFilter> {
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
	public ConnectionFilter get() {
		return new ConnectionFilter() {
			@Override
			public void onConnect(ConnectionSession session) {
				if (contains(session.getRemoteAddr())) {
					super.onConnect(session);
				} else {
					disconnect();
					onDisconnect();
				}
			}
		};
	}
}