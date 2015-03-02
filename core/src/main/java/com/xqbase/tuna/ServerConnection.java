package com.xqbase.tuna;

import java.util.function.Supplier;

/** A {@link Supplier} to create {@link Connection} */
@FunctionalInterface
public interface ServerConnection extends Supplier<Connection> {
	/** Adds a {@link ConnectionFilter} as a filter into the network end of each generated connection */
	public default ServerConnection appendFilter(Supplier<? extends ConnectionFilter> serverFilter) {
		return () -> get().appendFilter(serverFilter.get());
	}
}