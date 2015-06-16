package com.xqbase.tuna.proxy;

import com.xqbase.tuna.proxy.util.LinkedEntry;

class TimeoutEntry extends LinkedEntry {
	ClientConnection client;
	long expire;

	TimeoutEntry(ClientConnection client, long expire) {
		this.client = client;
		this.expire = expire;
	}

	@Override
	public TimeoutEntry getPrev() {
		return (TimeoutEntry) super.getPrev();
	}

	@Override
	public TimeoutEntry getNext() {
		return (TimeoutEntry) super.getNext();
	}
}