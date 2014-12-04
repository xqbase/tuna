package com.xqbase.net.misc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Supplier;

import com.xqbase.net.Filter;

/** A "ServerFilter" to limit bytes, requests and connections from the same IP. */
public class DoSServerFilter implements Supplier<Filter> {
	static int[] getData(String ip, HashMap<String, int[]> map, int length) {
		int[] data = map.get(ip);
		if (data == null) {
			data = new int[length];
			Arrays.fill(data, 0);
			map.put(ip, data);
		}
		return data;
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
	 * Called when bytes, requests or connections reached to the limit.
	 *
	 * @param connections_
	 * @param requests_
	 * @param bytes_
	 */
	protected void onBlock(Filter filter, int connections_, int requests_, int bytes_) {
		filter.disconnect();
		filter.onDisconnect();
	}

	long accessed = System.currentTimeMillis();
	int period, bytes, requests, connections;

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
		this.connections = connections;
		this.requests = requests;
		this.bytes = bytes;
	}

	@Override
	public Filter get() {
		return new Filter() {
			@Override
			public void onRecv(byte[] b, int off, int len) {
				checkTimeout();
				int[] requests_ = getData(getRemoteAddr(), requestsMap, 2);
				requests_[1] += len;
				if (bytes > 0 && requests_[1] > bytes) {
					onBlock(this, requests_[0], requests_[1], 0);
				} else {
					super.onRecv(b, off, len);
				}
			}

			private boolean connected = false;

			private void disconnect_() {
				if (connected) {
					connected = false;
					getData(getRemoteAddr(), connectionsMap, 1)[0] --;
				}
			}

			@Override
			public void onConnect() {
				connected = true;
				String ip = getRemoteAddr();
				int[] connections_ = getData(ip, connectionsMap, 1);
				connections_[0] ++;
				checkTimeout();
				int[] requests_ = getData(ip, requestsMap, 2);
				requests_[0] ++;
				if ((connections > 0 && connections_[0] > connections) ||
						(requests > 0 && requests_[0] > requests)) {
					onBlock(this, connections_[0], requests_[0], 0);
				} else {
					super.onConnect();
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
		};
	}
}