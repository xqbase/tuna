package com.xqbase.tuna.ssl;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Random;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.misc.ForwardServer;
import com.xqbase.tuna.util.Bytes;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

public class TestSNI {
	private static Random random = new Random();

	private static void setKeyEntry(KeyStore ks, String dn, long expire)
			throws GeneralSecurityException, IOException {
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024);
		KeyPair keyPair = kpg.genKeyPair();
		long now = System.currentTimeMillis();
		X509CertInfo info = new X509CertInfo();
		info.set("version", new CertificateVersion(2));
		info.set("serialNumber", new CertificateSerialNumber(new BigInteger(128, random)));
		info.set("algorithmID",
				new CertificateAlgorithmId(AlgorithmId.get("SHA1withRSA")));
		X500Name x500Name = new X500Name(dn);
		info.set("subject", x500Name);
		info.set("key", new CertificateX509Key(keyPair.getPublic()));
		info.set("validity", new CertificateValidity(new
				Date(now), new Date(now + expire)));
		info.set("issuer", x500Name);
		X509CertImpl cert = new X509CertImpl(info);
		cert.sign(keyPair.getPrivate(), "SHA1withRSA");
		ks.setKeyEntry(Bytes.toHexLower(Bytes.random(16)),
				keyPair.getPrivate(), new char[0], new X509Certificate[] {cert});
	}

	public static void main(String[] args) throws Exception {
		KeyStore ks = KeyStore.getInstance("JKS");
		ks.load(null, null);
		setKeyEntry(ks, "CN=www.xqbase.com", 86400000);
		setKeyEntry(ks, "CN=www.chess-wizard.com", 86400000);
		KeyManagerFactory kmf = KeyManagerFactory.getInstance("NewSunX509");
		kmf.init(ks, new char[0]);
		SSLContext sslc = SSLContext.getInstance("TLS");
		sslc.init(kmf.getKeyManagers(), SSLManagers.DEFAULT_TRUST_MANAGERS, null);

		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(new ForwardServer(connector, "ns2.xqbase.com", 80).appendFilter(() ->
					new SSLFilter(connector, connector, null, sslc, SSLFilter.SERVER_NO_AUTH)), 443);
			connector.doEvents();
		}
	}
}