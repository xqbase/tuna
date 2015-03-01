package com.xqbase.tuna.allinone;

import com.xqbase.tuna.ConnectionHandler;

public interface VirtualHandler extends ConnectionHandler {
	public int getConnectionID();
	public ConnectionHandler getAiOHandler();
}