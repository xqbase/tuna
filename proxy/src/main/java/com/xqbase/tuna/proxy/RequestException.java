package com.xqbase.tuna.proxy;

import com.xqbase.tuna.http.HttpPacket;

public class RequestException extends Exception {
	private static final long serialVersionUID = 1L;

	private HttpPacket response;

	public RequestException(int status, String reason,
			byte[] body, String... headerPairs) {
		response = new HttpPacket(status, reason, body, headerPairs);
	}

	public RequestException(int status, String... headPairs) {
		this(status, ProxyContext.getReason(status),
				ProxyContext.getDefaultErrorPage(status), headPairs);
	}

	public HttpPacket getResponse() {
		return response;
	}
}