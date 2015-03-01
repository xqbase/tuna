package com.xqbase.tuna.misc;

import com.xqbase.tuna.ConnectionWrapper;
import com.xqbase.tuna.TimerHandler;

public class BandwidthFilter extends ConnectionWrapper {
	private long period = 0, limit = 0;
	private long next = System.currentTimeMillis(), bytesRecv = 0;
	private TimerHandler timer;

	public BandwidthFilter(TimerHandler timer) {
		this.timer = timer;
	}

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
	public void setBufferSize(int bufferSize) {
		super.setBufferSize(bufferSize <= 0 || period <= 0 || limit <= 0 ?
				bufferSize : (int) Math.min(bufferSize, limit));
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		super.onRecv(b, off, len);
		long period_ = getPeriod();
		long limit_ = getLimit();
		if (period_ != period || limit_ != limit) {
			period = period_;
			limit = limit_;
		}
		if (period <= 0 || limit <= 0) {
			super.setBufferSize(MAX_BUFFER_SIZE);
			return;
		}
		bytesRecv += len;
		if (bytesRecv < limit) {
			long now = System.currentTimeMillis();
			if (now > next) {
				next = now + period;
				bytesRecv = len;
			}
			super.setBufferSize((int) Math.min(limit - bytesRecv, MAX_BUFFER_SIZE));
		} else {
			super.setBufferSize(0);
			timer.postAtTime(() -> {
				next += period;
				bytesRecv = 0;
				super.setBufferSize((int) Math.min(limit, MAX_BUFFER_SIZE));
			}, next);
		}
	}
}