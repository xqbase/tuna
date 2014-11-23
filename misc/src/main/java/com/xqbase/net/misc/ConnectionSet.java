package com.xqbase.net.misc;

import java.util.LinkedHashSet;

import com.xqbase.net.Connection;
import com.xqbase.net.Filter;
import com.xqbase.net.FilterFactory;

/**
 * A set of {@link Connection}s.
 * This set also implements the interface {@link FilterFactory}<p>
 *
 * If this set is added into the FilterFactory list of a connector or a server connection,
 * a connected or accepted connection will automatically be added into this set,
 * and a closed connection will automatically be removed from this set.
 */
public class ConnectionSet extends LinkedHashSet<Connection>
		implements FilterFactory, AutoCloseable {
	private static final long serialVersionUID = 1L;

	@Override
	public Filter createFilter() {
		return new Filter() {
			@Override
			protected void onConnect() {
				add(getConnection());
				super.onConnect();
			}

			@Override
			protected void onDisconnect(boolean active) {
				super.onDisconnect(active);
				remove(getConnection());
			}
		};
	}

	/** Removes and closes all of the connections from this set. */
	@Override
	public void close() {
		Connection[] connections = toArray(new Connection[0]);
		for (Connection connection : connections) {
			connection.disconnect();
		}
	}
}