package com.xqbase.tuna.proxy;

import java.util.Map;

import com.xqbase.tuna.http.HttpPacket;

@FunctionalInterface
public interface RequestListener {
	public void accept(Map<String, Object> bindings,
			HttpPacket request) throws RequestException;
}