package com.xqbase.net.misc;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import com.xqbase.net.Filter;
import com.xqbase.net.FilterFactory;

/**
 * A set of trusted IPs. This set also implements the interface
 * {@link FilterFactory}, which can prevent connecting with untrusted IPs.
 */
public class IPTrustSet extends HashSet<String> implements FilterFactory {
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
	public Filter createFilter() {
		return new Filter() {
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