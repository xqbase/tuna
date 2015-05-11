package com.xqbase.tuna.mux;

import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.cert.Certificate;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.CertificateException;
import javax.security.cert.X509Certificate;

import com.xqbase.util.Log;

class MuxSSLSession implements SSLSession {
	private static Principal getPrincipal(Certificate[] certificates) {
		if (certificates == null || certificates.length == 0 ||
				!(certificates[0] instanceof java.security.cert.X509Certificate)) {
			return null;
		}
		return ((java.security.cert.X509Certificate)
				certificates[0]).getSubjectX500Principal();
	}

	private byte[] id;
	private String protocol, cipherSuite, peerHost;
	private int peerPort;
	private Certificate[] peerCertificates, localCertificates;

	MuxSSLSession(byte[] id, String protocol, String cipherSuite, String peerHost,
			int peerPort, Certificate[] localCertificates, Certificate[] peerCertificates) {
		this.id = id;
		this.protocol = protocol;
		this.cipherSuite = cipherSuite;
		this.peerHost = peerHost;
		this.peerPort = peerPort;
		this.peerCertificates = peerCertificates;
		this.localCertificates = localCertificates;
	}

	@Override
	public byte[] getId() {
		return id;
	}

	@Override
	public SSLSessionContext getSessionContext() {
		return null;
	}

	@Override
	public long getCreationTime() {
		return 0;
	}

	@Override
	public long getLastAccessedTime() {
		return 0;
	}

	@Override
	public void invalidate() {/**/}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public void putValue(String key, Object value) {/**/}

	@Override
	public Object getValue(String paramString) {
		return null;
	}

	@Override
	public void removeValue(String paramString) {/**/}

	@Override
	public String[] getValueNames() {
		return null;
	}

	@Override
	public Certificate[] getPeerCertificates() {
		return peerCertificates;
	}

	@Override
	public Certificate[] getLocalCertificates() {
		return localCertificates;
	}

	@Override
	public X509Certificate[] getPeerCertificateChain() {
		X509Certificate[] chain = new X509Certificate[peerCertificates.length];
		try {
			for (int i = 0; i < peerCertificates.length; i ++) {
				chain[i] = X509Certificate.getInstance(peerCertificates[i].getEncoded());
			}
		} catch (CertificateException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		}
		return chain;
	}

	@Override
	public Principal getPeerPrincipal() {
		return getPrincipal(peerCertificates);
	}

	@Override
	public Principal getLocalPrincipal() {
		return getPrincipal(localCertificates);
	}

	@Override
	public String getCipherSuite() {
		return cipherSuite;
	}

	@Override
	public String getProtocol() {
		return protocol;
	}

	@Override
	public String getPeerHost() {
		return peerHost;
	}

	@Override
	public int getPeerPort() {
		return peerPort;
	}

	@Override
	public int getPacketBufferSize() {
		return 0;
	}

	@Override
	public int getApplicationBufferSize() {
		return 0;
	}
}