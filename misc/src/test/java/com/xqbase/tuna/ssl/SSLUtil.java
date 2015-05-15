package com.xqbase.tuna.ssl;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

public class SSLUtil {
	public static SSLContext getSSLContext(CertKey certKey, CertMap certMap) {
		try {
			// 1. KeyManagerFactory
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(certKey == null ? null : certKey.toKeyStore("", "JKS"), new char[0]);
			// 2. TrustManagerFactory
			TrustManager[] trustManagers;
			if (certMap == null) {
				trustManagers = SSLManagers.DEFAULT_TRUST_MANAGERS;
			} else {
				TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
				tmf.init(certMap.exportJks());
				trustManagers = tmf.getTrustManagers();
			}
			// 3. SSLContext
			SSLContext sslc = SSLContext.getInstance("TLS");
			sslc.init(kmf.getKeyManagers(), trustManagers, null);
			return sslc;
		} catch (IOException | GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}
}