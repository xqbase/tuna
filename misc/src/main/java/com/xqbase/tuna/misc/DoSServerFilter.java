package com.xqbase.tuna.misc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Supplier;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionSession;

/** A "ServerFilter" to limit bytes, requests and connections from the same IP. */
public class DoSServerFilter implements Supplier<ConnectionFilter> {
	class DoSFilter extends ConnectionFilter {
		private String remoteAddr;

		private int[] getData(HashMap<String, int[]> map, int length) {
			int[] data = map.get(remoteAddr);
			if (data == null) {
				data = new int[length];
				Arrays.fill(data, 0);
				map.put(remoteAddr, data);
			}
			return data;
		}

		@Override
		public void onRecv(byte[] b, int off, int len) {
			checkTimeout();
			int[] requests__ = getData(requestsMap, 2);
			requests__[1] += len;
			if (bytes_ > 0 && requests__[1] > bytes_) {
				onBlock(this, remoteAddr, requests__[0], requests__[1], 0);
			} else {
				super.onRecv(b, off, len);
			}
		}

		private boolean connected = false;

		private void disconnect_() {
			if (connected) {
				connected = false;
				getData(connectionsMap, 1)[0] --;
			}
		}

		@Override
		public void onConnect(ConnectionSession session) {
			remoteAddr = session.getRemoteAddr();
			connected = true;
			int[] connections__ = getData(connectionsMap, 1);
			connections__[0] ++;
			checkTimeout();
			int[] requests__ = getData(requestsMap, 2);
			requests__[0] ++;
			if ((connections_ > 0 && connections__[0] > connections_) ||
					(requests_ > 0 && requests__[0] > requests_)) {
				onBlock(this, remoteAddr, connections__[0], requests__[0], 0);
			} else {
				super.onConnect(session);
			}
		}

		@Override
		public void onDisconnect() {
			super.onDisconnect();
			disconnect_();
		}

		@Override
		public void disconnect() {
			super.disconnect();
			disconnect_();
		}
	}

	/** To record how many connections from one IP. */
	HashMap<String, int[]> connectionsMap = new HashMap<>();
	/** To record how many requests and bytes from one IP. */
	HashMap<String, int[]> requestsMap = new HashMap<>();

	void checkTimeout() {
		long now = System.currentTimeMillis();
		if (now > accessed + period) {
			accessed = now;
			requestsMap.clear();
		}
	}

	/**
	 * Called when connections, requests or bytes reach to the limit.
	 *
	 * @param remoteAddr
	 * @param connections
	 * @param requests
	 * @param bytes
	 */
	protected void onBlock(ConnectionFilter filter, String remoteAddr,
			int connections, int requests, int bytes) {
		filter.disconnect();
		filter.onDisconnect();
	}

	long accessed = System.currentTimeMillis();
	int period, connections_, requests_, bytes_;

	/**
	 * Creates an DoSServerFilter with the given parameters
	 *
	 * @param period - The period, in milliseconds.
	 * @param connections - Maximum concurrent connections the same IP.
	 * @param requests - Maximum requests (connection events) in the period from the same IP.
	 * @param bytes - Maximum sent bytes in the period from the same IP.
	 */
	public DoSServerFilter(int period, int connections, int requests, int bytes) {
		setParameters(period, connections, requests, bytes);
	}

	/**
	 * Reset the parameters
	 *
	 * @param period - The period, in milliseconds.
	 * @param connections - Maximum concurrent connections the same IP.
	 * @param requests - Maximum requests (connection events) in the period from the same IP.
	 * @param bytes - Maximum sent bytes in the period from the same IP.
	 */
	public void setParameters(int period, int connections, int requests, int bytes) {
		this.period = period;
		this.connections_ = connections;
		this.requests_ = requests;
		this.bytes_ = bytes;
	}

	@Override
	public ConnectionFilter get() {
		return new DoSFilter();
	}
}