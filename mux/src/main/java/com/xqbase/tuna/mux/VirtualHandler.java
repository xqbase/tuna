package com.xqbase.tuna.mux;

import com.xqbase.tuna.ConnectionHandler;

public interface VirtualHandler extends ConnectionHandler {
	public ConnectionHandler getMuxHandler();
	public int getConnectionID();
}