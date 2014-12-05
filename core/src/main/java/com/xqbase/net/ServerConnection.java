package com.xqbase.net;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** A {@link Supplier} to create {@link Connection} */
@FunctionalInterface
public interface ServerConnection extends Supplier<Connection> {
	/** Adds a {@link ConnectionWrapper} as a filter into the network end of each generated connection */
	public default ServerConnection appendFilter(Supplier<? extends ConnectionWrapper> serverFilter) {
		return () -> get().appendFilter(serverFilter.get());
	}
	/** @param executor */
	public default void setExecutor(Executor executor) {/**/}
}