package com.xqbase.tuna.ldap;

import java.io.IOException;
import java.util.logging.Logger;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.der.DerFilter;
import com.xqbase.tuna.misc.DumpFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;

public class TestLdap {
	public static void main(String[] args) {
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("LDAP.", 16777216, 10));

		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(((ServerConnection) LdapConnection::new).appendFilter(DerFilter::new).
					appendFilter(() -> new DumpFilter().setDumpStream(System.out)), 389);
			connector.doEvents();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Conf.closeLogger(Log.getAndSet(logger));
	}
}