package com.xqbase.net;

/**
 * A Factory to create {@link Filter}s,
 * applied to {@link Connector} and {@link ServerConnection}.
 */
@FunctionalInterface
public interface FilterFactory {
	/** @return A new <b>Filter</b>. */
	public Filter createFilter();
}