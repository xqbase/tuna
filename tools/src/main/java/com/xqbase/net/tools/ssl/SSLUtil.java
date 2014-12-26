package com.xqbase.net.tools.ssl;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import com.xqbase.util.Time;

public class SSLUtil {
	static SSLContext getSSLContext(String dn)
			throws IOException, GeneralSecurityException {
		KeyManager[] kms;
		if (dn == null) {
			kms = new KeyManager[0];
		} else {
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, null);
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(1024);
			KeyPair keyPair = kpg.genKeyPair();
			long now = System.currentTimeMillis();
			X509CertInfo info = new X509CertInfo();
			info.set("version", new CertificateVersion(2));
			info.set("serialNumber", new CertificateSerialNumber(0));
			info.set("algorithmID",
					new CertificateAlgorithmId(AlgorithmId.get("SHA1withRSA")));
			X500Name x500Name = new X500Name(dn);
			info.set("subject", x500Name);
			info.set("key", new CertificateX509Key(keyPair.getPublic()));
			info.set("validity", new CertificateValidity(new
					Date(now), new Date(now + Time.WEEK * 52)));
			info.set("issuer", x500Name);
			X509CertImpl cert = new X509CertImpl(info);
			cert.sign(keyPair.getPrivate(), "SHA1withRSA");
			ks.setKeyEntry("", keyPair.getPrivate(), new char[0],
					new X509Certificate[] {cert});
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, new char[0]);
			kms = kmf.getKeyManagers();
		}
		SSLContext sslc = SSLContext.getInstance("TLS");
		sslc.init(kms, new X509TrustManager[] {
			new X509TrustManager() {
				@Override
				public void checkClientTrusted(X509Certificate[] certs, String s) {/**/}

				@Override
				public void checkServerTrusted(X509Certificate[] certs, String s) {/**/}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			}
		}, null);
		return sslc;
	}
}