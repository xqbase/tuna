package com.xqbase.net;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** A {@link Supplier} to create {@link Listener} */
@FunctionalInterface
public interface ServerListener extends Supplier<Listener> {
	/** Adds a Filter into the network end of the listener */
	public default ServerListener appendFilter(Supplier<? extends Filter> serverFilter) {
		return () -> get().appendFilter(serverFilter.get());
	}
	/** @param executor */
	public default void setExecutor(Executor executor) {/**/}
}