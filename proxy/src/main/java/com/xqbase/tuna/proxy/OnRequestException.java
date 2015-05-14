package com.xqbase.tuna.proxy;

import com.xqbase.tuna.http.HttpPacket;

public class OnRequestException extends Exception {
	private static final long serialVersionUID = 1L;

	private HttpPacket response;

	public OnRequestException(HttpPacket response) {
		this.response = response;
	}

	public OnRequestException(int status, String reason,
			String body, String... headerPair) {
		response = HttpPacket.createResponse(status, reason, body, headerPair);
	}

	public HttpPacket getResponse() {
		return response;
	}
}