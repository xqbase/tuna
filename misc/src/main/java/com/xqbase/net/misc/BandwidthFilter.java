package com.xqbase.net.misc;

import com.xqbase.net.ConnectionWrapper;

public class BandwidthFilter extends ConnectionWrapper {
	private long period = 0, limit = 0;
	private long next = System.currentTimeMillis(), bytesRecv = 0;

	public long getPeriod() {
		return period;
	}

	public void setPeriod(long period) {
		this.period = period;
	}

	public long getLimit() {
		return limit;
	}

	public void setLimit(long limit) {
		this.limit = limit;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		super.onRecv(b, off, len);
		long period_ = getPeriod();
		long limit_ = getLimit();
		if (period_ != period || limit_ != limit) {
			period = period_;
			limit = limit_;
			setBufferSize(period <= 0 || limit <= 0 ? MAX_BUFFER_SIZE :
					(int) Math.min(limit, MAX_BUFFER_SIZE));
		}
		if (period <= 0 || limit <= 0) {
			return;
		}
		bytesRecv += len;
		if (bytesRecv < limit) {
			long now = System.currentTimeMillis();
			if (now > next) {
				next = now + period;
				bytesRecv = len;
			}
			setBufferSize((int) Math.min(limit - bytesRecv, MAX_BUFFER_SIZE));
		} else {
			next += period;
			setBufferSize(0);
			postAtTime(() -> {
				bytesRecv = 0;
				setBufferSize((int) Math.min(limit, MAX_BUFFER_SIZE));
			}, next);
		}
	}
}