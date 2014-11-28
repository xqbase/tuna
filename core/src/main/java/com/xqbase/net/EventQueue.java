package com.xqbase.net;

public interface EventQueue {
	/** Invokes a {@link Runnable} in main thread */
	public void invokeLater(Runnable runnable);
}