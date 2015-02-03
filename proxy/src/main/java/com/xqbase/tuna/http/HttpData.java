package com.xqbase.tuna.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.xqbase.tuna.util.ByteArrayQueue;

public class HttpData {
	public static final int PHASE_BEGIN = 0;
	public static final int PHASE_HEAD = 1;
	public static final int PHASE_BODY = 2;
	public static final int PHASE_CHUNK_SIZE = 3;
	public static final int PHASE_CHUNK_DATA = 4;
	public static final int PHASE_TRAIL = 5;
	public static final int PHASE_END = 6;

	/** Preset <b>false</b> for reading request and <b>true</b> for reading response */
	public boolean response = false;
	/**
	 * Preset <b>true</b> for reading response for HEAD,
	 * only available when {@link #response} is set
	 */
	public boolean head = false;
	/**
	 * Phase for reading, see {@link #PHASE_BEGIN}, {@link #PHASE_HEAD}, 
	 * {@link #PHASE_BODY}, {@link #PHASE_TRAIL} and {@link #PHASE_END}
	 */
	public int phase = PHASE_BEGIN;
	/** Request Method, only available when {@link #response} is unset */
	public String method = null;
	/** Request Path, only available when {@link #response} is unset */
	public String path = null;
	/** Response Status, only available when {@link #response} is set */
	public int status = 0;
	/** Byte to Read, -1 for an HTTP/1.0 request or response */
	public int bytesToRead = 0; 
	/** HTTP Headers for request and response */
	public LinkedHashMap<String, ArrayList<String>> headers = new LinkedHashMap<>();
	/** HTTP Body for request and response */
	public ByteArrayQueue body = new ByteArrayQueue();

	private StringBuilder line = new StringBuilder();
	/**
	 * @return <b>0</b> for complete line (read <b>len</b> bytes),
	 * <b>&gt; 0</b> for incomplete line (number of bytes read).
	 */
	private int readLine(byte[] b, int off, int len) {
		for (int i = 0; i < len; i ++) {
			char c = (char) b[off + i];
			if (c == '\n') {
				return i + 1;
			}
			if (c != '\r') {
				line.append(c);
			}
		}
		return 0;
	}

	/** @return number of bytes read */
	private void readHeader() {
		int colon = line.indexOf(": ");
		if (colon < 0) {
			line.setLength(0);
			return;
		}
		String key = line.substring(0, colon);
		StringBuilder sb = new StringBuilder();
		for (String s : key.split("-")) {
			if (!s.isEmpty()) {
				sb.append(s.charAt(0)).append(s.substring(1).toLowerCase()).append('-');
			}
		}
		key = sb.substring(0, sb.length() - 1);
		ArrayList<String> values = headers.get(key);
		if (values == null) {
			values = new ArrayList<>();
			headers.put(key, values);
		}
		values.add(sb.substring(colon + 2));
		line.setLength(0);
	}

	/** @return number of bytes read, -1 for a bad request */
	public int read(byte[] b, int off, int len) {
		int bytesRead = 0;
		if (phase == PHASE_BEGIN) {
			int n = readLine(b, off, len);
			if (n == 0) {
				return len;
			}
			bytesRead = n;
			String[] ss = line.toString().split(" ");
			if (ss.length < 3) {
				return -1;
			}
			String proto = (response ? ss[0] : ss[2]).toUpperCase();
			if (proto.equals("HTTP/1.0")) {
				bytesToRead = -1;
			} else if (!proto.equals("HTTP/1.1")) {
				return -1;
			}
			if (response) {
				try {
					status = Integer.parseInt(ss[1]);
				} catch (NumberFormatException e) {
					return -1;
				}
			} else {
				method = ss[0].toUpperCase();
				path = ss[1];
			}
			line.setLength(0);
			phase = PHASE_HEAD;
		}

		if (phase == PHASE_HEAD) {
			while (true) {
				int n = readLine(b, off + bytesRead, len - bytesRead);
				if (n == 0) {
					return len;
				}
				bytesRead += n;
				if (line.length() == 0) {
					break;
				}
				readHeader();
			}
		}
		phase = PHASE_BODY;
		if (bytesToRead == 0 && !head) {
			ArrayList<String> values = headers.get("Transfer-Encoding");
			if (values != null && values.size() == 1 &&
					values.get(0).toLowerCase().equals("chunked")) {
				phase = PHASE_CHUNK_SIZE;
			} else {
				values = headers.get("Content-Length");
				if (values != null && values.size() == 1) {
					try {
						bytesToRead = Integer.parseInt(values.get(0));
					} catch (NumberFormatException e) {
						return -1;
					}
					if (bytesToRead < 0) {
						return -1;
					}
				}
			}
		}

		if (phase == PHASE_BODY) {
			if (bytesToRead < 0 || bytesRead + bytesToRead > len) {
				body.add(b, off + bytesRead, len - bytesRead);
				return len; 
			}
			body.add(b, off + bytesRead, bytesToRead);
			phase = PHASE_END;
			return bytesRead + bytesToRead;
		}

		// phase > PHASE_BODY
		while (true) {
			if (phase == PHASE_CHUNK_SIZE) {
				// TODO
			}
			int n = readLine(b, off + bytesRead, len - bytesRead);
			if (n == 0) {
				return len;
			}
			bytesRead += n;
		}

		return -1;
	}
}