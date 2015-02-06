package com.xqbase.tuna.http;

import java.io.IOException;

public class HttpPacketException extends IOException {
	private static final long serialVersionUID = 1L;

	public static enum Type {
		BEGIN_LINE("Begin Line"),
		PROTOCOL("Protocol"),
		STATUS("Status"),
		CONTENT_LENGTH("Content-Length"),
		CHUNK_SIZE("Chunk Size"),
		DESTINATION("Destination"),
		HOST("Host"),
		PORT("Port"),
		;

		private String value;

		private Type(String value) {
			this.value = value;
		}

		@Override
		public String toString() {
			return value;
		}
	}

	private Type type;
	private String value;

	public HttpPacketException(Type type, String value) {
		super("Invalid " + type + ": \"" + value + "\"");
		this.type = type;
		this.value = value;
	}

	public Type getType() {
		return type;
	}

	public String getValue() {
		return value;
	}
}