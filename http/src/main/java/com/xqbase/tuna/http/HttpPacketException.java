package com.xqbase.tuna.http;

import java.io.IOException;

public class HttpPacketException extends IOException {
	private static final long serialVersionUID = 1L;

	public static final String HEADER_SIZE = "Header Size Exceeds Limit";
	public static final String START_LINE = "Invalid Start Line";
	public static final String VERSION = "Unrecognized Version";
	public static final String STATUS = "Invalid Status";
	public static final String CONTENT_LENGTH = "Invalid Content-Length";
	public static final String CHUNK_SIZE = "Invalid Chunk Size";

	private String type, value;

	public HttpPacketException(String type, String value) {
		super(type + ": \"" + value + "\"");
		this.type = type;
		this.value = value;
	}

	public String getType() {
		return type;
	}

	public String getValue() {
		return value;
	}
}