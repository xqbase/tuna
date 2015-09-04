package com.xqbase.tuna.ldap;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sun.security.util.DerInputStream;
import sun.security.util.DerOutputStream;
import sun.security.util.DerValue;

import com.sun.jndi.ldap.Ber;
import com.sun.jndi.ldap.BerEncoder;
import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

public class LdapConnection implements Connection {
	private static final String BASE_CONTEXT = "ou=people";

	private static final int LDAP_VERSION3 = 0x03;

	private static final int LDAP_REQ_BIND = 0x60;
	private static final int LDAP_REQ_SEARCH = 0x63;
	private static final int LDAP_REQ_UNBIND = 0x42;
	private static final int LDAP_REQ_ABANDON = 0x50;

	private static final int LDAP_REP_BIND = 0x61;
	private static final int LDAP_REP_SEARCH = 0x64;
	private static final int LDAP_REP_RESULT = 0x65;

    private static final int LDAP_OTHER = 80;
	private static final int LDAP_SUCCESS = 0;
	private static final int LDAP_INVALID_CREDENTIALS = 49;

    private static final int LDAP_FILTER_PRESENT = (byte) 0x87;

	private static final int LBER_ENUMERATED = 0x0a;
	private static final int LBER_SET = 0x31;
	private static final int LBER_SEQUENCE = 0x30;

	private static final int SCOPE_BASE_OBJECT = 0;

	/** Convert LDAP Request to Sequence */
	private static DerValue[] getSequence(DerValue der) throws IOException {
		byte[] data = der.getDataBytes();
		try (DerOutputStream out = new DerOutputStream()) {
			out.putTag(DerValue.tag_Sequence, false, (byte) 0);
			out.putLength(data.length);
			out.write(data);
			return new DerInputStream(out.toByteArray()).getSequence(0);
		}
	}

	private boolean utf8 = false;
	private String username = null, password = null;
	private ConnectionHandler handler;

	private void sendRootDSE(int msgId) throws IOException {
		Log.d("LOG_LDAP_SEND_ROOT_DSE");

		Map<String, Object> attributes = new HashMap<>();
		attributes.put("objectClass", "top");

		sendEntry(msgId, "Root DSE", attributes);
	}

	/**
	 * Send Base Context
	 *
	 * @param currentMessageId current message id
	 * @throws IOException on error
	 */
	private void sendBaseContext(int currentMessageId) throws IOException {
		List<String> objectClasses = new ArrayList<>();
		objectClasses.add("top");
		objectClasses.add("organizationalUnit");
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("objectClass", objectClasses);
		attributes.put("description", "SMTP-to-LDAP Gateway");
		sendEntry(currentMessageId, BASE_CONTEXT, attributes);
	}

	private void sendEntry(int currentMessageId, String dn, Map<String, Object> attributes) throws IOException {
		BerEncoder ber = new BerEncoder();
		ber.reset();
		ber.beginSeq(Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR);
		ber.encodeInt(currentMessageId);
		ber.beginSeq(LDAP_REP_SEARCH);
		ber.encodeString(dn, utf8);
		ber.beginSeq(LBER_SEQUENCE);
		for (Map.Entry<String, Object> entry : attributes.entrySet()) {
			ber.beginSeq(LBER_SEQUENCE);
			ber.encodeString(entry.getKey(), utf8);
			ber.beginSeq(LBER_SET);
			Object values = entry.getValue();
			if (values instanceof String) {
				ber.encodeString((String) values, utf8);
			} else if (values instanceof List) {
				for (Object value : (Iterable<?>) values) {
					ber.encodeString((String) value, utf8);
				}
			} else {
				throw new IOException("EXCEPTION_UNSUPPORTED_VALUE" + ": " + values);
			}
			ber.endSeq();
			ber.endSeq();
		}
		ber.endSeq();
		ber.endSeq();
		ber.endSeq();
		handler.send(ber.getBuf(), 0, ber.getDataLen());
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		Bytes.dump(System.out, b, off, len);
		int msgId = 0;
		try {
			DerInputStream der = new DerInputStream(b, off, len);
			DerValue[] ders = der.getSequence(0);
			if (ders.length < 2) {
				throw new IOException("At least 2 DerValues");
			}
			msgId = ders[0].getInteger();
			switch (ders[1].getTag()) {
			case LDAP_REQ_BIND:
				ders = getSequence(ders[1]);
				if (ders.length < 3) {
					throw new IOException("At least 3 DerValues for LDAP_REQ_BIND");
				}
				utf8 = ders[0].getInteger() == LDAP_VERSION3;
				username = new String(ders[1].getDataBytes());
				password = new String(ders[2].getDataBytes());
				Log.d(username + ":" + password);
				send(msgId, LDAP_REP_BIND, LDAP_SUCCESS, "");
				// send(msgId, LDAP_REP_BIND, LDAP_INVALID_CREDENTIALS, "");
				break;
			case LDAP_REQ_UNBIND:
				// TODO Sign Out
				break;
			case LDAP_REQ_SEARCH:
				ders = getSequence(ders[1]);
				if (ders.length < 5) {
					throw new IOException("At least 5 DerValues for LDAP_REQ_SEARCH");
				}
				String dn = new String(ders[0].getDataBytes());
				Log.d("LDAP_REQ_SEARCH: " + dn);
				int scope = ders[1].getEnumerated();
				// int derefAliases = ders[2].getEnumerated();
				// int sizeLimit = ders[3].getInteger();
				// int timeLimit = ders[4].getInteger();
				// boolean typesOnly = ders[5].getBoolean();
				// Parse LDAP Filter
				if (ders[6].getTag() == LDAP_FILTER_PRESENT) {
					String attributeName = new String(ders[6].getDataBytes());
					Log.d("LDAP_FILTER_PRESENT: " + attributeName);
				} else {
					Bytes.dump(System.out, ders[6].getDataBytes());
				}
				// TODO Parse Returning Attributes
				// SearchRunnable searchRunnable = new SearchRunnable(currentMessageId, dn, scope);
				// searchRunnable.run();
				HashMap<String, Object> ldapPerson = new HashMap<>();
				if (scope == SCOPE_BASE_OBJECT) {
					if (dn.isEmpty()) {
						sendRootDSE(msgId);
					} else if (BASE_CONTEXT.equals(dn)) {
						sendBaseContext(msgId);
					} else if (dn.startsWith("uid=") && dn.indexOf(',') > 0) {
						sendEntry(msgId, "uid=" + "uid" /* TODO uid */ + dn.substring(dn.indexOf(',')), ldapPerson);
					} else {
						Log.d("LOG_LDAP_REQ_SEARCH_INVALID_DN" + ": " + msgId + ", " + dn);
					}
				} else if (BASE_CONTEXT.equalsIgnoreCase(dn)) {
					sendEntry(msgId, "uid=" + "uid" /* TODO uid */ + ", " + dn, ldapPerson);
				} else {
					Log.d("LOG_LDAP_REQ_SEARCH_INVALID_DN" + ": " + msgId + ", " + dn);
				}
				// sendClient(currentMessageId, LDAP_REP_RESULT, LDAP_SIZE_LIMIT_EXCEEDED, "");
				send(msgId, LDAP_REP_RESULT, LDAP_SUCCESS, "");
				// TODO Return Result
				break;
			case LDAP_REQ_ABANDON:
				// Do Nothing
				break;
			default:
				Log.d("Unsupported Operation: " + ders[1].getTag());
				send(msgId, LDAP_REP_RESULT, LDAP_OTHER, "Unsupported operation");
			}
		} catch (IOException e) {
			String message = e.getMessage();
			if (message == null) {
				message = e.getClass().getName();
			}
			// Log and Dump Data
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			pw.println(message);
			Bytes.dump(pw, b, off, len);
			Log.w(sw.toString());
			// Send Error
			send(msgId, LDAP_REP_RESULT, LDAP_OTHER, message);
		}
	}

	private void send(int msgId, int op, int status, String message) {
		try (
			DerOutputStream der0 = new DerOutputStream();
			DerOutputStream der1 = new DerOutputStream();
			DerOutputStream der2 = new DerOutputStream();
		) {
			der2.putEnumerated(status);
			der2.putOctetString(message.getBytes());
			der1.putInteger(msgId);
			der1.write((byte) op, der2.toByteArray());
			der0.write(DerValue.tag_Sequence, der1.toByteArray());
			handler.send(der0.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}