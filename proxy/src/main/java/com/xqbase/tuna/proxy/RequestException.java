package com.xqbase.tuna.proxy;

import com.xqbase.tuna.http.HttpPacket;

public class RequestException extends Exception {
	private static final long serialVersionUID = 1L;

	private HttpPacket response;

	public RequestException(HttpPacket response) {
		this.response = response;
	}

	public RequestException(int status, String reason,
			String body, String... headerPair) {
		response = new HttpPacket(status, reason, body, headerPair);
	}

	public HttpPacket getResponse() {
		return response;
	}
}