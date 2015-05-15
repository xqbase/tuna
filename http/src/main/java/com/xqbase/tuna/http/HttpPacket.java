package com.xqbase.tuna.http;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.ByteArrayQueue;

public class HttpPacket {
	public static final int TYPE_REQUEST = 0;
	public static final int TYPE_RESPONSE = 1;
	public static final int TYPE_RESPONSE_HEAD = 2;
	public static final int TYPE_RESPONSE_HTTP10 = 3;

	private static final int PHASE_START = 0;
	private static final int PHASE_HEADER = 1;
	private static final int PHASE_BODY = 2;
	private static final int PHASE_CHUNK_SIZE = 3;
	private static final int PHASE_CHUNK_DATA = 4;
	private static final int PHASE_CHUNK_CRLF = 5;
	private static final int PHASE_TRAILER = 6;
	private static final int PHASE_END_CHUNK = 7;
	private static final int PHASE_END = 8;

	private static final byte[] SPACE = {' '};
	private static final byte[] COLON = {':', ' '};
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte[] HTTP10 = "HTTP/1.0".getBytes();
	private static final byte[] HTTP11 = "HTTP/1.1".getBytes();
	private static final byte[] FINAL_CRLF = {'0', '\r', '\n'};

	private int type = TYPE_REQUEST, phase = PHASE_START,
			headerLimit = 32768, headerSize = 0, status = 0, bytesToRead = 0;
	private boolean http10 = false;
	private String method = null, uri = null, reason = null;
	private LinkedHashMap<String, ArrayList<String>> headers = new LinkedHashMap<>();
	private ByteArrayQueue body = new ByteArrayQueue();
	private StringBuilder line = new StringBuilder();

	public HttpPacket() {/**/}

	public HttpPacket(int status, String reason,
			byte[] body, String... headerPairs) {
		type = TYPE_RESPONSE;
		this.status = status;
		this.reason = reason;
		getBody().add(body);
		setHeader("Content-Length", "" + body.length);
		for (int i = 0; i < headerPairs.length - 1; i += 2) {
			String key = headerPairs[i];
			String value = headerPairs[i + 1];
			if (key != null && value != null) {
				setHeader(key, value);
			}
		}
	}

	public HttpPacket(int status, String reason,
			String body, String... headerPairs) {
		this(status, reason, body.getBytes(), headerPairs);
	}

	public void reset() {
		phase = PHASE_START;
		headerSize = status = bytesToRead = 0;
		method = uri = reason = null;
		headers.clear();
		body.clear();
		line.setLength(0);
	}

	/** @return <code>true</code> for reading request */
	public boolean isRequest() {
		return type == TYPE_REQUEST;
	}

	/** @return <code>true</code> for reading response */
	public boolean isResponse() {
		return type != TYPE_REQUEST;
	}

	/** @param headerLimit */
	public void setHeaderLimit(int headerLimit) {
		this.headerLimit = headerLimit;
	}

	/**
	 * @param type {@link #TYPE_REQUEST}, {@link #TYPE_RESPONSE},
	 *        {@link #TYPE_RESPONSE_HEAD} or {@link #TYPE_RESPONSE_HTTP10}
	 */
	public void setType(int type) {
		this.type = type;
	}

	/** @return <code>true</code> for an HTTP/1.0 request or response */
	public boolean isHttp10() {
		return http10;
	}

	/**
	 * @param http10 <code>true</code> to write body in HTTP/1.0 mode,
	 *         regardless "Transfer-Encoding"
	 */
	public void setHttp10(boolean http10) {
		this.http10 = http10;
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

	/** @param method Request Method, only available for writing request */
	public void setMethod(String method) {
		this.method = method;
	}

	/** @return Request URI, only available for reading request */
	public String getUri() {
		return uri;
	}

	/** @param uri Request URI, only available for writing request */
	public void setUri(String uri) {
		this.uri = uri;
	}

	/** @return Response Status, only available for reading response */
	public int getStatus() {
		return status;
	}

	/** @param status Status Code, only available for writing response */
	public void setStatus(int status) {
		this.status = status;
	}

	/** @return Reason Phrase, only available for reading response */
	public String getReason() {
		return reason;
	}

	/** @param reason Reason Phrase, only available for writing response */
	public void setReason(String reason) {
		this.reason = reason;
	}

	/** @return HTTP Headers for request or response */
	public LinkedHashMap<String, ArrayList<String>> getHeaders() {
		return headers;
	}

	/** @return HTTP Body for request or response */
	public ByteArrayQueue getBody() {
		return body;
	}

	/** @return <code>true</code> for a complete line */
	private boolean readLine(ByteArrayQueue queue) throws HttpPacketException {
		byte[] b = queue.array();
		int begin = queue.offset();
		int end = begin + queue.length();
		for (int i = begin; i < end; i ++) {
			headerSize ++;
			if (headerSize > headerLimit) {
				throw new HttpPacketException(HttpPacketException.
						HEADER_SIZE, "" + headerLimit);
			}
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
			n = queue.length();
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
		int colon = line.indexOf(":");
		if (colon < 0) {
			line.setLength(0);
			return;
		}
		String originalKey = line.substring(0, colon);
		String key = originalKey.toUpperCase();
		ArrayList<String> values = headers.get(key);
		if (values == null) {
			values = new ArrayList<>();
			values.add(originalKey);
			headers.put(key, values);
		}
		values.add(line.substring(colon + 1).trim());
		line.setLength(0);
	}

	/** @throws HttpPacketException a bad request or response */
	public void read(ByteArrayQueue queue) throws HttpPacketException {
		if (phase == PHASE_START) {
			if (!readLine(queue) || line.length() == 0) {
				return;
			}
			String[] ss = line.toString().split(" ", 3);
			if (ss.length < 3) {
				throw new HttpPacketException(HttpPacketException.START_LINE, line.toString());
			}
			String version = (type == TYPE_REQUEST ? ss[2] : ss[0]).toUpperCase();
			if (version.equals("HTTP/1.0")) {
				http10 = true;
			} else if (!version.equals("HTTP/1.1")) {
				throw new HttpPacketException(HttpPacketException.VERSION, version);
			}
			if (type == TYPE_REQUEST) {
				method = ss[0];
				uri = ss[1];
				if (method.toUpperCase().equals("CONNECT")) {
					bytesToRead = -1;
				}
			} else {
				try {
					status = Integer.parseInt(ss[1]);
				} catch (NumberFormatException e) {
					throw new HttpPacketException(HttpPacketException.STATUS, ss[1]);
				}
				reason = ss[2];
				if (status == 101) {
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
			if (type != TYPE_RESPONSE_HEAD) {
				if (testHeader("TRANSFER-ENCODING", "chunked")) {
					phase = PHASE_CHUNK_SIZE;
				} else {
					String contentLength = getHeader("CONTENT-LENGTH");
					if (contentLength != null) {
						try {
							bytesToRead = Integer.parseInt(contentLength);
						} catch (NumberFormatException e) {
							bytesToRead = -1;
						}
						if (bytesToRead < 0) {
							throw new HttpPacketException(HttpPacketException.
									CONTENT_LENGTH, contentLength);
						}
					} else if (type == TYPE_RESPONSE_HTTP10 ||
							(type == TYPE_RESPONSE && http10)) {
						bytesToRead = -1;
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
					if (!readData(queue)) {
						return;
					}
					phase = PHASE_CHUNK_CRLF;
				}
				// Reset "headerSize" before reading Chunk Size 
				headerSize = 0;
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
					throw new HttpPacketException(HttpPacketException.CHUNK_SIZE, value);
				}
				line.setLength(0);
				if (bytesToRead == 0) {
					phase = PHASE_TRAILER;
					// Reset "headerSize" before reading Trailer
					headerSize = 0;
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

	public void endRead() {
		phase = PHASE_END;
	}

	public void continueRead() {
		phase = PHASE_BODY;
		bytesToRead = -1;
	}

	/**
	 * @param key Field Name in <b>Upper Case</b>
	 * @param value Field Value in <b>Lower Case</b>
	 */
	public boolean testHeader(String key, String value) {
		ArrayList<String> values = headers.get(key);
		if (values == null) {
			return false;
		}
		int size = values.size();
		for (int i = 1; i < size; i ++) {
			for (String s : values.get(i).split(",")) {
				if (s.trim().toLowerCase().equals(value)) {
					return true;
				}
			}
		}
		return false;
	}

	/** @param key Field Name in <b>Upper Case</b> */
	public String getHeader(String key) {
		ArrayList<String> values = headers.get(key);
		return values == null || values.size() != 2 ? null : values.get(1);
	}

	/** @param key Field Name in <b>Upper Case</b> */
	public void removeHeader(String key) {
		headers.remove(key);
	}

	/**
	 * @param key Field Name
	 * @param value Field Value
	 */
	public void setHeader(String key, String value) {
		String key_ = key.toUpperCase();
		ArrayList<String> values = headers.get(key_);
		if (values == null) {
			values = new ArrayList<>();
			headers.put(key_, values);
			values.add(key);
		} else if (values.isEmpty()) {
			// Should NOT Happen
			values.add(key);
		} else {
			String value_ = values.get(0);
			values.clear();
			values.add(value_);
		}
		values.add(value);
	}

	public void writeHeaders(ByteArrayQueue data) {
		Iterator<Map.Entry<String, ArrayList<String>>> it =
				headers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, ArrayList<String>> entry = it.next();
			it.remove();
			ArrayList<String> values = entry.getValue();
			int size = values.size();
			if (size < 1) {
				continue;
			}
			byte[] keyBytes = values.get(0).getBytes();
			for (int i = 1; i < size; i ++) {
				data.add(keyBytes).add(COLON).
						add(values.get(i).getBytes()).add(CRLF);
			}
		}
		data.add(CRLF);
	}

	/**
	 * @param data {@link ByteArrayQueue} to write into
	 * @param begin <code>true</code> to write entire Request or Response,
	 *        including Start Line and Headers,
	 *        and <code>false</code> to write Body and Trailers (if available) only
	 * @param forceChunked <code>true</code> to force to write in Chunked mode
	 */
	public void write(ByteArrayQueue data, boolean begin, boolean forceChunked) {
		if (begin) {
			if (type == TYPE_REQUEST) {
				data.add(method.getBytes()).add(SPACE).
						add(uri.getBytes()).add(SPACE).
						add(http10 ? HTTP10 : HTTP11).add(CRLF);
			} else {
				data.add(http10 ? HTTP10 : HTTP11).add(SPACE).
						add(("" + status).getBytes()).add(SPACE).
						add(reason.getBytes()).add(CRLF);
			}
			writeHeaders(data);
		}
		int length = body.length();
		if (forceChunked || (!http10 && phase >= PHASE_CHUNK_SIZE &&
				phase <= PHASE_END_CHUNK)) {
			if (length > 0) {
				data.add(Integer.toHexString(length).getBytes());
				data.add(CRLF);
				data.add(body.array(), body.offset(), length);
				body.remove(length);
				data.add(CRLF);
			}
			if (phase >= PHASE_END_CHUNK) {
				data.add(FINAL_CRLF);
				writeHeaders(data);
			}
		} else if (length > 0) {
			data.add(body.array(), body.offset(), length);
			body.remove(length);
		}
	}

	/**
	 * @param handler {@link ConnectionHandler} to write into
	 * @param begin <code>true</code> to write entire Request or Response,
	 *        including Start Line and Headers,
	 *        and <code>false</code> to write Body and Trailers (if available) only
	 * @param forceChunked <code>true</code> to force to write in Chunked mode
	 */
	public void write(ConnectionHandler handler, boolean begin, boolean forceChunked) {
		ByteArrayQueue data = new ByteArrayQueue();
		write(data, begin, forceChunked);
		handler.send(data.array(), data.offset(), data.length());
	}
}