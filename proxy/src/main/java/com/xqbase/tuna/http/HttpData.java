package com.xqbase.tuna.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.util.Numbers;

public class HttpData {
	private static final int PHASE_BEGIN = 0;
	private static final int PHASE_HEAD = 1;
	private static final int PHASE_BODY = 2;
	private static final int PHASE_TRAIL = 3;
	private static final int PHASE_END = 4;

	private static final int PROTO_ERROR = -1;
	private static final int PROTO_HTTP10 = 0;
	private static final int PROTO_HTTP11 = 1;

	private static int readLine(StringBuilder line, byte[] b, int off, int len) {
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

	private static int getProto(String proto) {
		String proto_ = proto.toUpperCase();
		return proto_.equals("HTTP/1.1") ? PROTO_HTTP11 :
				proto_.equals("HTTP/1.0") ? PROTO_HTTP10 : PROTO_ERROR;
	}

	public String method = null, path = null;
	public int status = 0;
	public boolean http10 = false;
	public LinkedHashMap<String, ArrayList<String>> headers = new LinkedHashMap<>();
	public ByteArrayQueue body = new ByteArrayQueue();

	private int phase = PHASE_BEGIN, length = 0;
	private StringBuilder line = new StringBuilder();

	public int read(byte[] b, int off, int len, boolean response) {
		if (phase == PHASE_BEGIN) {
			int bytesRead = readLine(line, b, off, len);
			if (bytesRead == 0) {
				return len;
			}
			String[] ss = line.toString().split(" ");
			if (ss.length < 3) {
				return -1;
			}
			int proto = getProto(response ? ss[0] : ss[2]);
			if (proto == PROTO_HTTP10) {
				http10 = true;
			} else if (proto != PROTO_HTTP11) {
				return -1;
			}
			if (response) {
				status = Numbers.parseInt(ss[1]);
			} else {
				method = ss[0].toUpperCase();
				path = ss[1];
			}
			line = new StringBuilder();
			phase = PHASE_HEAD;
			return bytesRead + read(b, off + bytesRead, len - bytesRead, response);
		}

		if (phase == PHASE_HEAD) {
			int bytesRead = readLine(line, b, off, len);
			if (bytesRead == 0) {
				return len;
			}
			int colon = line.indexOf(": ");
			if (colon < 0) {
				return -1;
			}
		}

		return -1;
	}
}