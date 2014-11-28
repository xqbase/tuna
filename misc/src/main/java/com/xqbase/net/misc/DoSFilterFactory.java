package com.xqbase.net.misc;

import java.util.ArrayDeque;
import java.util.HashMap;

import com.xqbase.net.Filter;
import com.xqbase.net.FilterFactory;

/** A {@link FilterFactory} to limit bytes, requests and connections from the same IP. */
public class DoSFilterFactory implements FilterFactory {
	/** To record how many bytes, requests and connections from one IP. */
	public static class IPTracker {
		String ip;
		long timeout;
		int bytes = 0, requests = 0, connections = 0;

		public String getIp() {
			return ip;
		}

		public int getBytes() {
			return bytes;
		}

		public int getRequests() {
			return requests;
		}

		public int getConnections() {
			return connections;
		}
	}

	class DoSFilter extends Filter {
		private IPTracker ipTracker = null;

		@Override
		public void onRecv(byte[] b, int off, int len) {
			ipTracker.bytes += len;
			if (bytes > 0 && ipTracker.bytes > bytes) {
				onBlock(this, ipTracker);
			} else {
				super.onRecv(b, off, len);
			}
		}

		@Override
		public void onConnect() {
			String ip = getRemoteAddr();
			ipTracker = ipMap.get(ip);
			if (ipTracker == null) {
				ipTracker = new IPTracker();
				ipTracker.ip = ip;
				ipTracker.timeout = System.currentTimeMillis() + period;
				ipMap.put(ip, ipTracker);
				timeoutQueue.offer(ipTracker);
			}
			ipTracker.requests ++;
			ipTracker.connections ++;
			if ((requests > 0 && ipTracker.requests > requests) ||
					(connections > 0 && ipTracker.connections > connections)) {
				onBlock(this, ipTracker);
			} else {
				super.onConnect();
			}
		}

		@Override
		public void onDisconnect() {
			super.onDisconnect();
			if (ipTracker != null) {
				ipTracker.connections --;
			}
		}

		@Override
		public void disconnect() {
			super.disconnect();
			if (ipTracker != null) {
				ipTracker.connections --;
			}
		}
	}

	/**
	 * Called when bytes, requests or connections reached to the limit.
	 * @param connection - The blocked connection.
	 * @param ipTracker - The IPTracker.
	 */
	protected void onBlock(Filter filter, IPTracker ipTracker) {
		filter.disconnect();
		filter.onDisconnect();
	}

	int period, bytes, requests, connections;
	HashMap<String, IPTracker> ipMap = new HashMap<>();
	ArrayDeque<IPTracker> timeoutQueue = new ArrayDeque<>();

	/**
	 * Creates an DoSFilterFactory with the given parameters 
	 * @param period - The period, in milliseconds.
	 * @param bytes - Maximum sent bytes in the period from the same IP.
	 * @param requests - Maximum requests (connection events) in the period from the same IP.
	 * @param connections - Maximum concurrent connections the same IP.
	 */
	public DoSFilterFactory(int period, int bytes, int requests, int connections) {
		setParameters(period, bytes, requests, connections);
	}

	/**
	 * Reset the parameters
	 * @param period - The period, in milliseconds.
	 * @param bytes - Maximum sent bytes in the period from the same IP.
	 * @param requests - Maximum requests (connection events) in the period from the same IP.
	 * @param connections - Maximum concurrent connections the same IP.
	 */
	public void setParameters(int period, int bytes, int requests, int connections) {
		this.period = period;
		this.bytes = bytes;
		this.requests = requests;
		this.connections = connections;
	}

	public void onTimer() {
		long now = System.currentTimeMillis();
		IPTracker ipTracker;
		while ((ipTracker = timeoutQueue.peek()) != null && now > ipTracker.timeout) {
			timeoutQueue.poll();
			if (ipTracker.connections == 0) {
				ipMap.remove(ipTracker.ip);
			} else {
				ipTracker.timeout = now + period;
				ipTracker.bytes = ipTracker.requests = 0;
				timeoutQueue.offer(ipTracker);
			}
		}
	}

	@Override
	public DoSFilter createFilter() {
		return new DoSFilter();
	}
}