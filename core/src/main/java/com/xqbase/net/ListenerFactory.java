package com.xqbase.net;

/** A Factory to create {@link Listener} */
@FunctionalInterface
public interface ListenerFactory {
	/** @return A new {@link Listener}. */
	public Listener onAccept();
	/** @param eventQueue */
	public default void setEventQueue(EventQueue eventQueue) {/**/}
}