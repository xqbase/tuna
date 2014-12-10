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
			if (period <= 0 || limit <= 0 || limit >= MAX_BUFFER_SIZE * 4) {
				setBufferSize(MAX_BUFFER_SIZE);
			} else if (limit > 8192) {
				setBufferSize((int) ((limit + 3) / 4));
			} else {
				setBufferSize((int) limit);
			}
		}
		if (period <= 0 || limit <= 0) {
			return;
		}
		bytesRecv += len;
		if (bytesRecv < limit) {
			long now = System.currentTimeMillis();
			if (now > next) {
				next = now;
				bytesRecv = len;
			}
		} else {
			next += period_;
			blockRecv(true);
			postAtTime(() -> {
				bytesRecv = 0;
				blockRecv(false);
			}, next);
		}
	}
}