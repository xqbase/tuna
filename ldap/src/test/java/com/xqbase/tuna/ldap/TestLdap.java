package com.xqbase.tuna.ldap;

import java.io.IOException;

import com.xqbase.tuna.ConnectorImpl;

public class TestLdap {
	public static void main(String[] args) {
		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(LdapConnection::new, 389);
			connector.doEvents();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}