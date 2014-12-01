package com.xqbase.net;

import java.util.concurrent.Executor;

/** A Factory to create {@link Listener} */
@FunctionalInterface
public interface ListenerFactory {
	/** @return A new {@link Listener}. */
	public Listener onAccept();
	/** @param executor */
	public default void setExecutor(Executor executor) {/**/}
}