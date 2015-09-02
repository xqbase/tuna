package com.xqbase.tuna.ldap;

import java.io.IOException;

import com.sun.jndi.ldap.Ber;
import com.sun.jndi.ldap.BerDecoder;
import com.sun.jndi.ldap.BerEncoder;
import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
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

	private static final int LBER_ENUMERATED = 0x0a;
	private static final int LBER_SET = 0x31;
	private static final int LBER_SEQUENCE = 0x30;

	private static final int SCOPE_BASE_OBJECT = 0;

	private boolean utf8 = false;
	private String userName = null, password = null;
	private ConnectionHandler handler;

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		BerDecoder ber = new BerDecoder(b, off, len);
		int msgId = 0;
		try {
			ber.parseSeq(null);
			msgId = ber.parseInt();
			int requestOperation = ber.peekByte();

			if (requestOperation == LDAP_REQ_BIND) {
				ber.parseSeq(null);
				utf8 = ber.parseInt() == LDAP_VERSION3;
				userName = ber.parseString(utf8);
				password = ber.parseStringWithTag(Ber.ASN_CONTEXT, utf8, null);

					if (userName.length() > 0 && password.length() > 0) {
						Log.d("LOG_LDAP_REQ_BIND_USER" + ": " + msgId + ", " + userName);
						// TODO session = ExchangeSessionFactory.getInstance(userName, password);
						
						Log.d("LOG_LDAP_REQ_BIND_SUCCESS");
						sendClient(msgId, LDAP_REP_BIND, LDAP_SUCCESS, "");
						// TODO if Invalid Credentials:
						Log.d("LOG_LDAP_REQ_BIND_INVALID_CREDENTIALS");
						sendClient(msgId, LDAP_REP_BIND, LDAP_INVALID_CREDENTIALS, "");
					} else {
						Log.d("LOG_LDAP_REQ_BIND_ANONYMOUS" + ": " + msgId);
						// anonymous bind
						sendClient(msgId, LDAP_REP_BIND, LDAP_SUCCESS, "");
					}

			} else if (requestOperation == LDAP_REQ_UNBIND) {
				Log.d("LOG_LDAP_REQ_UNBIND" + ": " + msgId);
				/* TODO
				if (session != null) {
					session = null;
				} */
			} else if (requestOperation == LDAP_REQ_SEARCH) {
				ber.parseSeq(null);
				String dn = ber.parseString(utf8);
				int scope = ber.parseEnumeration();
				/*int derefAliases =*/
				ber.parseEnumeration();
				int sizeLimit = ber.parseInt();
				if (sizeLimit > 100 || sizeLimit == 0) {
					sizeLimit = 100;
				}
				// Time Limit
				ber.parseInt();
				/// Types Only
				ber.parseBoolean();
				// TODO Parse LDAP Filter
				// TODO Parse Returning Attributes
				// SearchRunnable searchRunnable = new SearchRunnable(currentMessageId, dn, scope);
				// searchRunnable.run();
				// TODO Return Result
			} else if (requestOperation == LDAP_REQ_ABANDON) {
				// Skip ABANDON
				Log.d("LOG_LDAP_REQ_ABANDON_SEARCH" + ": " + msgId);
			} else {
				Log.d("LOG_LDAP_UNSUPPORTED_OPERATION" + ": " + requestOperation);
				sendClient(msgId, LDAP_REP_RESULT, LDAP_OTHER, "Unsupported operation");
			}
		} catch (IOException e) {
			// TODO Dump Data
			String message = e.getMessage();
			if (message == null) {
				message = e.getClass().getName();
			}
			sendClient(msgId, LDAP_REP_RESULT, LDAP_OTHER, message);
		}
	}

	private void sendClient(int msgId, int op, int status, String message) {
		BerEncoder ber = new BerEncoder();
		ber.beginSeq(Ber.ASN_SEQUENCE | Ber.ASN_CONSTRUCTOR);
		ber.encodeInt(msgId);
		ber.beginSeq(op);
		ber.encodeInt(status, LBER_ENUMERATED);
		try {
			// dn
			ber.encodeString("", utf8);
			// error message
			ber.encodeString(message, utf8);
			ber.endSeq();
			ber.endSeq();
		} catch (IOException e) {
			Log.e(e);
		}
		handler.send(ber.getBuf(), 0, ber.getDataLen());
	}
}