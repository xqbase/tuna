package com.xqbase.tuna.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.xqbase.tuna.util.ByteArrayQueue;

public class HttpPacket {
	public static enum Type {
		REQUEST, RESPONSE, RESPONSE_FOR_HEAD, RESPONSE_FOR_CONNECT
	}

	private static final int PHASE_BEGIN = 0;
	private static final int PHASE_HEADER = 1;
	private static final int PHASE_BODY = 2;
	private static final int PHASE_CHUNK_SIZE = 3;
	private static final int PHASE_CHUNK_DATA = 4;
	private static final int PHASE_CHUNK_CRLF = 5;
	private static final int PHASE_TRAILER = 6;
	private static final int PHASE_END_CHUNK = 7;
	private static final int PHASE_END = 8;

	private Type type;
	private int phase, status, bytesToRead;
	private String method = null, path = null;
	private LinkedHashMap<String, ArrayList<String[]>> headers;
	private ByteArrayQueue body;
	private StringBuilder line;

	public HttpPacket(Type type) {
		this.type = type;
		reset();
	}

	public void reset() {
		phase = PHASE_BEGIN;
		status = bytesToRead = 0;
		method = path = null;
		headers.clear();
		body.clear();
		line.setLength(0);
	}

	/** @return <code>true</code> for reading request */
	public boolean isRequest() {
		return type == Type.REQUEST;
	}

	/** @return <code>true</code> for reading response */
	public boolean isResponse() {
		return type != Type.REQUEST;
	}

	/** @return <code>true</code> for an HTTP/1.0 request or response */
	public boolean isHttp10() {
		return bytesToRead < 0;
	}

	/** @return <code>true</code> for an HTTP/1.1 request or response */
	public boolean isHttp11() {
		return bytesToRead >= 0;
	}

	/** @return <code>true</code> for a complete header for request or response */ 
	public boolean isCompleteHeader() {
		return phase >= PHASE_BODY;
	}

	/** @return <code>true</code> for a complete request or response */ 
	public boolean isComplete() {
		return phase >= PHASE_END_CHUNK;
	}

	/** @return <code>true</code> for a chunked request or response */ 
	public boolean isChunked() {
		return phase >= PHASE_CHUNK_SIZE && phase <= PHASE_END_CHUNK;
	}

	/** @return Request Method, only available for reading request */
	public String getMethod() {
		return method;
	}

	/** @return Request Path, only available for reading request */
	public String getPath() {
		return path;
	}

	/** @return Response Status, only available for reading response */
	public int getStatus() {
		return status;
	}

	/** @return HTTP Headers for request or response */
	public LinkedHashMap<String, ArrayList<String[]>> getHeaders() {
		return headers;
	}

	/** @return HTTP Body for request or response */
	public ByteArrayQueue getBody() {
		return body;
	}

	/** @return <code>true</code> for a complete line */
	private boolean readLine(ByteArrayQueue queue) {
		byte[] b = queue.array();
		int begin = queue.offset();
		int end = begin + queue.length();
		for (int i = begin; i < end; i ++) {
			char c = (char) b[i];
			if (c == '\n') {
				queue.remove(i - begin + 1);
				return true;
			}
			if (c != '\r') {
				line.append(c);
			}
		}
		queue.remove(end - begin);
		return false;
	}

	/** @return <code>true</code> for a complete body or chunk */
	private boolean readData(ByteArrayQueue queue) {
		int n;
		if (bytesToRead < 0) {
			n = bytesToRead;
		} else if (bytesToRead > queue.length()) {
			n = queue.length();
			bytesToRead -= n;
		} else {
			n = bytesToRead;
			bytesToRead = 0;
		}
		body.add(queue.array(), queue.offset(), n);
		queue.remove(n);
		return bytesToRead == 0;
	}

	/** @return number of bytes read */
	private void readHeader() {
		int colon = line.indexOf(": ");
		if (colon < 0) {
			line.setLength(0);
			return;
		}
		String originalKey = line.substring(0, colon);
		String key = originalKey.toUpperCase();
		ArrayList<String[]> values = headers.get(key);
		if (values == null) {
			values = new ArrayList<>();
			headers.put(key, values);
		}
		values.add(new String[] {originalKey, line.substring(colon + 2)});
		line.setLength(0);
	}

	/** @throws HttpPacketException a bad request or response */
	public void read(ByteArrayQueue queue) throws HttpPacketException {
		if (phase == PHASE_BEGIN) {
			if (!readLine(queue)) {
				return;
			}
			String[] ss = line.toString().split(" ", 3);
			if (ss.length < 3) {
				throw new HttpPacketException(HttpPacketException.Type.BEGIN_LINE, line.toString());
			}
			String proto = (type == Type.REQUEST ? ss[2] : ss[0]).toUpperCase();
			if (proto.equals("HTTP/1.0")) {
				bytesToRead = -1;
			} else if (!proto.equals("HTTP/1.1")) {
				throw new HttpPacketException(HttpPacketException.Type.PROTOCOL, proto);
			}
			if (type == Type.REQUEST) {
				method = ss[0];
				path = ss[1];
				if (method.toUpperCase().equals("CONNECT")) {
					bytesToRead = -1;
				}
			} else {
				try {
					status = Integer.parseInt(ss[1]);
				} catch (NumberFormatException e) {
					throw new HttpPacketException(HttpPacketException.Type.STATUS, ss[1]);
				}
				if (type == Type.RESPONSE_FOR_CONNECT && status == 200) {
					bytesToRead = -1;
				}
			}
			line.setLength(0);
			phase = PHASE_HEADER;
		}

		if (phase == PHASE_HEADER) {
			while (true) {
				if (!readLine(queue)) {
					return;
				}
				if (line.length() == 0) {
					break;
				}
				readHeader();
			}
			phase = PHASE_BODY;
			if (bytesToRead == 0 && type != Type.RESPONSE_FOR_HEAD) {
				ArrayList<String[]> values = headers.get("TRANSFER-ENCODING");
				if (values != null) {
					for (String[] pair : values) {
						if (pair[1].toLowerCase().equals("chunked")) {
							phase = PHASE_CHUNK_SIZE;
							break;
						}
					}
				}
				if (phase == PHASE_BODY) {
					values = headers.get("CONTENT-LENGTH");
					if (values != null && values.size() == 1) {
						String value = values.get(0)[1];
						try {
							bytesToRead = Integer.parseInt(value);
						} catch (NumberFormatException e) {
							bytesToRead = -1;
						}
						if (bytesToRead < 0) {
							throw new HttpPacketException(HttpPacketException.Type.CONTENT_LENGTH, value);
						}
					}
				}
			}
		}

		if (phase == PHASE_BODY) {
			if (readData(queue)) {
				phase = PHASE_END;
			}
			return;
		}

		// phase == PHASE_CHUNK_SIZE/DATA/CRLF 
		if (phase < PHASE_TRAILER) {
			while (true) {
				if (phase == PHASE_CHUNK_DATA) {
					readData(queue);
					if (phase == PHASE_CHUNK_DATA) {
						return;
					}
					if (!readData(queue)) {
						return;
					}
					phase = PHASE_CHUNK_CRLF;
				}
				if (!readLine(queue)) {
					return;
				}
				if (phase == PHASE_CHUNK_CRLF) {
					line.setLength(0);
					phase = PHASE_CHUNK_SIZE;
					continue;
				}
				int space = line.indexOf(" ");
				String value = space < 0 ? line.toString() : line.substring(0, space);
				try {
					bytesToRead = Integer.parseInt(value, 16);
				} catch (NumberFormatException e) {
					bytesToRead = -1;
				}
				if (bytesToRead < 0) {
					throw new HttpPacketException(HttpPacketException.Type.CHUNK_SIZE, value);
				}
				line.setLength(0);
				if (bytesToRead == 0) {
					phase = PHASE_TRAILER;
					break;
				}
				phase = PHASE_CHUNK_DATA;
			}
		}

		if (phase == PHASE_TRAILER) {
			while (true) {
				if (!readLine(queue)) {
					return;
				}
				if (line.length() == 0) {
					break;
				}
				readHeader();
			}
			phase = PHASE_END_CHUNK;
		}
	}
}