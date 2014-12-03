package com.xqbase.net;

import java.util.concurrent.Executor;

/** A Factory to create {@link Listener} */
@FunctionalInterface
public interface ListenerFactory {
	/** @return A new {@link Listener}. */
	public Listener onAccept();
	/** Adds a Filter into the network end of the listener */
	public default ListenerFactory appendFilter(FilterFactory filterFactory) {
		return () -> onAccept().appendFilter(filterFactory.createFilter());
	}
	/** @param executor */
	public default void setExecutor(Executor executor) {/**/}
}