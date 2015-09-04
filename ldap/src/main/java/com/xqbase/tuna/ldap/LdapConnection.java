package com.xqbase.tuna.ldap;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import sun.security.util.DerInputStream;
import sun.security.util.DerOutputStream;
import sun.security.util.DerValue;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Log;

public class LdapConnection implements Connection {
	private static final String BASE_CONTEXT = "ou=people";
	private static final String NAMING_CONTEXTS = "namingContexts";

	private static final byte LDAP_REQ_BIND = 0x60;
	private static final byte LDAP_REQ_SEARCH = 0x63;
	private static final byte LDAP_REQ_UNBIND = 0x42;
	private static final byte LDAP_REQ_ABANDON = 0x50;

	private static final byte LDAP_REP_BIND = 0x61;
	private static final byte LDAP_REP_SEARCH = 0x64;
	private static final byte LDAP_REP_RESULT = 0x65;

	private static final byte LDAP_SUCCESS = 0;
	private static final byte LDAP_INVALID_CREDENTIALS = 0x31;
    private static final byte LDAP_OTHER = 0x50;
    private static final byte LDAP_FILTER_PRESENT = (byte) 0x87;

	private static final int LDAP_VERSION3 = 0x03;
	private static final int SCOPE_BASE_OBJECT = 0;

	private Charset charset = Charset.defaultCharset();
	private String username = null, password = null;
	private ConnectionHandler handler;

	private static void putAttribute(HashMap<String, List<String>> attributes,
			String key, String... values) {
		attributes.put(key, Arrays.asList(values));
	}

	private void putString(DerOutputStream der, String value) throws IOException {
		der.putOctetString(value.getBytes(charset));
	}

	private String getString(DerValue der) throws IOException {
		return new String(der.getDataBytes(), charset);
	}

	private void sendRootDSE(int msgId, HashSet<String> selection) {
		HashMap<String, List<String>> attributes = new HashMap<>();
		putAttribute(attributes, "objectClass", "top");
		if (selection.contains(NAMING_CONTEXTS)) {
			putAttribute(attributes, NAMING_CONTEXTS, BASE_CONTEXT);
		}
		sendEntry(msgId, "Root DSE", attributes);
	}

	private void sendBaseContext(int msgId) {
		HashMap<String, List<String>> attributes = new HashMap<>();
		putAttribute(attributes, "objectClass", "top", "organizationalUnit");
		putAttribute(attributes, "description", "SMTP-to-LDAP Gateway");
		sendEntry(msgId, BASE_CONTEXT, attributes);
	}

	private void sendEntry(int msgId, String dn,
			HashMap<String, List<String>> attributes) {
		try (
			DerOutputStream der0 = new DerOutputStream();
			DerOutputStream der1 = new DerOutputStream();
		) {
			putString(der0, dn);
			attributes.forEach((k, v) -> {
				try (
					DerOutputStream der2 = new DerOutputStream();
					DerOutputStream der3 = new DerOutputStream();
				) {
					putString(der2, k);
					for (String value : v) {
						putString(der3, value);
					}
					der2.write(DerValue.tag_Set, der3);
					der1.write(DerValue.tag_Sequence, der2);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			der0.write(DerValue.tag_Sequence, der1);
			send(msgId, LDAP_REP_SEARCH, der0);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void sendResult(int msgId, byte op, int status, String message) {
		try (DerOutputStream der = new DerOutputStream()) {
			der.putEnumerated(status);
			putString(der, ""); // dn
			putString(der, message);
			send(msgId, op, der);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void send(int msgId, byte op, DerOutputStream der) throws IOException {
		try (
			DerOutputStream der0 = new DerOutputStream();
			DerOutputStream der1 = new DerOutputStream();
		) {
			der1.putInteger(msgId);
			der1.write(op, der);
			der0.write(DerValue.tag_Sequence, der1);
			handler.send(der0.toByteArray());
		}
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		int msgId = 0;
		try {
			DerValue[] ders = new DerInputStream(b, off, len).getSequence(0);
			if (ders.length < 2) {
				throw new IOException("At least 2 DerValues");
			}
			msgId = ders[0].getInteger();
			DerInputStream op = ders[1].getData();
			switch (ders[1].getTag()) {
			case LDAP_REQ_BIND:
				charset = op.getInteger() == LDAP_VERSION3 ?
						StandardCharsets.UTF_8 : Charset.defaultCharset();
				username = getString(op.getDerValue());
				password = getString(op.getDerValue());
				// TODO Auth
				Log.d(username + ":" + password);
				if (Boolean.TRUE.booleanValue()) {
					sendResult(msgId, LDAP_REP_BIND, LDAP_SUCCESS, "");
				} else {
					sendResult(msgId, LDAP_REP_BIND, LDAP_INVALID_CREDENTIALS, "");
				}
				break;
			case LDAP_REQ_UNBIND:
				// TODO Sign Out
				break;
			case LDAP_REQ_SEARCH:
				String dn = getString(op.getDerValue());
				Log.d("LDAP_REQ_SEARCH: " + dn);
				int scope = op.getEnumerated();
				op.getEnumerated(); // derefAliases
				op.getInteger(); // sizeLimit
				op.getInteger(); // timeLimit
				op.getDerValue(); // typesOnly
				// filter
				DerValue filter = op.getDerValue();
				if (filter.getTag() == LDAP_FILTER_PRESENT) {
					String attributeName = getString(filter);
					Log.d("LDAP_FILTER_PRESENT: " + attributeName);
				} else {
					Bytes.dump(System.out, filter.getDataBytes());
				}
				// attributes
				HashSet<String> selection = new HashSet<>();
				for (DerValue attr : op.getSequence(0)) {
					selection.add(getString(attr));
				}
				HashMap<String, List<String>> ldapPerson = new HashMap<>();
				if (scope == SCOPE_BASE_OBJECT) {
					if (dn.isEmpty()) {
						sendRootDSE(msgId, selection);
					} else if (BASE_CONTEXT.equals(dn)) {
						sendBaseContext(msgId);
					} else if (dn.startsWith("uid=") && dn.indexOf(',') > 0) {
						sendEntry(msgId, "uid=" + username + dn.substring(dn.indexOf(',')), ldapPerson);
					} else {
						Log.d("LOG_LDAP_REQ_SEARCH_INVALID_DN" + ": " + msgId + ", " + dn);
					}
				} else if (BASE_CONTEXT.equalsIgnoreCase(dn)) {
					sendEntry(msgId, "uid=" + username + ", " + dn, ldapPerson);
				} else {
					Log.d("LOG_LDAP_REQ_SEARCH_INVALID_DN" + ": " + msgId + ", " + dn);
				}
				// sendClient(currentMessageId, LDAP_REP_RESULT, LDAP_SIZE_LIMIT_EXCEEDED, "");
				sendResult(msgId, LDAP_REP_RESULT, LDAP_SUCCESS, "");
				// TODO Return Result
				break;
			case LDAP_REQ_ABANDON:
				// Do Nothing
				break;
			default:
				Log.d("Unsupported Operation: " + ders[1].getTag());
				sendResult(msgId, LDAP_REP_RESULT, LDAP_OTHER, "Unsupported operation");
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
			sendResult(msgId, LDAP_REP_RESULT, LDAP_OTHER, message);
		}
	}
}