package com.xqbase.tuna.http;

import java.io.IOException;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.util.ByteArrayQueue;

public class TestHttp10 {
	static final byte[] BAD_REQUEST =
			("HTTP/1.0 400 Bad Request\r\n" +
			"Content-Length: 0\r\n\r\n").getBytes();
	static final byte[] RESP_LENGTH =
			("HTTP/1.0 200 OK\r\n" +
			"Content-Length: 16\r\n\r\n").getBytes();
	static final byte[] RESP_CHUNKED =
			("HTTP/1.0 200 OK\r\n\r\n").getBytes();
	static final byte[] RESP_BODY =
			("0123456789ABCDEF").getBytes();

	public static void main(String[] args) {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(() -> new Connection() {
				private boolean begun = false;
				private ConnectionHandler handler;
				private HttpPacket request = new HttpPacket();

				@Override
				public void setHandler(ConnectionHandler handler) {
					this.handler = handler;
				}

				@Override
				public void onRecv(byte[] b, int off, int len) {
					if (begun) {
						return;
					}
					try {
						request.read(new ByteArrayQueue(b, off, len));
					} catch (HttpPacketException e) {
						handler.send(BAD_REQUEST);
						handler.disconnect();
						return;
					}
					if (!request.isComplete()) {
						return;
					}
					if (request.getUri().equals("/32")) {
						handler.send(RESP_CHUNKED);
						handler.send(RESP_BODY);
						begun = true;
						connector.postDelayed(() -> {
							handler.send(RESP_BODY);
							handler.disconnect();
						}, 1000);
					} else {
						handler.send(RESP_LENGTH);
						handler.send(RESP_BODY);
						handler.disconnect();
					}
				}
			}, 80);
			connector.doEvents();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}