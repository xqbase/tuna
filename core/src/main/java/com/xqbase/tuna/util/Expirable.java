package com.xqbase.tuna.util;

public interface Expirable<T> {
	public long getExpire();
	public void setExpire(long expire);
	public void setTimeoutEntry(LinkedEntry<T> timeoutEntry);
}