package com.xqbase.tuna.mux;

import java.io.IOException;
import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.CertificateException;
import javax.security.cert.X509Certificate;

import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Log;

class MuxSSLSession implements SSLSession {
	private static final Certificate[] EMPTY_CERTIFICATES = {};

	private static HashMap<String, Integer> proto2Ver = new HashMap<>();
	private static HashMap<Integer, String> ver2Proto = new HashMap<>();

	private static HashMap<String, Integer> cipherName2Id = new HashMap<>();
	private static HashMap<Integer, String> cipherId2Name = new HashMap<>();

	private static void addProtoVer(String proto, int ver) {
		Integer ver_ = Integer.valueOf(ver);
		proto2Ver.put(proto, ver_);
		ver2Proto.put(ver_, proto);
	}

	static {
		// addProtoVer("NONE", -1);
		addProtoVer("SSLv2Hello", 2);
		addProtoVer("SSLv3", 768);
		addProtoVer("TLSv1", 769);
		addProtoVer("TLSv1.1", 770);
		addProtoVer("TLSv1.2", 771);

		try {
			Class<?> clazz = Class.forName("sun.security.ssl.CipherSuite");
			Field idMapField = clazz.getDeclaredField("idMap");
			idMapField.setAccessible(true);
			Field nameField = clazz.getDeclaredField("name");
			nameField.setAccessible(true);
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) idMapField.get(null)).entrySet()) {
				Integer id = (Integer) entry.getKey();
				String name = (String) nameField.get(entry.getValue());
				cipherName2Id.put(name, id);
				cipherId2Name.put(id, name);
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	private static Principal getPrincipal(Certificate[] certificates) {
		if (certificates == null || certificates.length == 0 ||
				!(certificates[0] instanceof java.security.cert.X509Certificate)) {
			return null;
		}
		return ((java.security.cert.X509Certificate)
				certificates[0]).getSubjectX500Principal();
	}

	private byte[] id;
	private String cipherSuite, protocol, peerHost;
	private int peerPort;
	private Certificate[] peerCertificates, localCertificates;

	MuxSSLSession(SSLSession ssls) {
		id = ssls.getId();
		id = id == null ? new byte[0] : id;
		cipherSuite = ssls.getCipherSuite();
		protocol = ssls.getProtocol();
		peerHost = ssls.getPeerHost();
		peerPort = ssls.getPeerPort();
		try {
			peerCertificates = ssls.getPeerCertificates();
		} catch (IOException e) {
			peerCertificates = null;
		}
		localCertificates = ssls.getLocalCertificates();
	}

	private static Certificate[] parseCerts(byte[] b, int off, int len) {
		if (len == 0) {
			return null;
		}
		ByteArrayQueue baq = new ByteArrayQueue();
		baq.add(b, off, len);
		try {
			return CertificateFactory.getInstance("X509").
					generateCertificates(baq.getInputStream()).toArray(EMPTY_CERTIFICATES);
		} catch (GeneralSecurityException e) {
			return null;
		}
	}

	/** see {@link MuxPacket#CONNECTION_CONNECT } */
	MuxSSLSession(byte[] b, int off, int len) throws IOException {
		if (len < 6) {
			throw new IOException();
		}
		int idLen = b[off] & 0xFF;
		int hostLen = b[off + 1] & 0xFF;
		int peerCertsLen = Bytes.toShort(b, off + 2);
		int localCertsLen = Bytes.toShort(b, off + 4);
		if (len < 12 + idLen + hostLen + peerCertsLen + localCertsLen) {
			throw new IOException();
		}
		id = Bytes.sub(b, off + 6, idLen);
		cipherSuite = cipherId2Name.get(Integer.valueOf(Bytes.toShort(b, off + idLen + 6)));
		cipherSuite = (cipherSuite == null ? "SSL_NULL_WITH_NULL_NULL" : cipherSuite);
		protocol = ver2Proto.get(Integer.valueOf(Bytes.toShort(b, off + idLen + 8)));
		protocol = (protocol == null ? "NONE" : protocol);
		peerHost = hostLen == 0 ? null : new String(Bytes.sub(b, off + idLen + 10, hostLen));
		peerPort = Bytes.toShort(b, off + idLen + hostLen + 10);
		peerCertificates = parseCerts(b, off + idLen + hostLen + 12, peerCertsLen);
		localCertificates = parseCerts(b,
				off + idLen + hostLen + peerCertsLen + 12, localCertsLen);
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
		if (peerCertificates == null) {
			return null;
		}
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

	private static byte[] encodeCerts(Certificate[] certs) {
		if (certs == null) {
			return new byte[0];
		}
		try {
			return CertificateFactory.getInstance("X509").
					generateCertPath(Arrays.asList(certs)).getEncoded("PKCS7");
		} catch (GeneralSecurityException e) {
			return new byte[0];
		}
	}

	/** see {@link MuxPacket#CONNECTION_CONNECT } */
	byte[] getEncoded() {
		byte[] peerHostBytes = (peerHost == null ? new byte[0] : peerHost.getBytes());
		byte[] peerCertsBytes = encodeCerts(peerCertificates);
		byte[] localCertsBytes = encodeCerts(localCertificates);
		int idLen = id.length;
		int hostLen = peerHostBytes.length;
		int peerCertsLen = peerCertsBytes.length;
		int localCertsLen = localCertsBytes.length;
		byte[] b = new byte[12 + idLen + hostLen + peerCertsLen + localCertsLen];
		b[0] = (byte) idLen;
		b[1] = (byte) hostLen;
		Bytes.setShort(peerCertsLen, b, 2);
		Bytes.setShort(localCertsLen, b, 4);
		System.arraycopy(id, 0, b, 6, id.length);
		Integer cipherId_ = cipherName2Id.get(cipherSuite);
		Bytes.setShort(cipherId_ == null ? 0 : cipherId_.intValue(), b, 6 + idLen);
		Integer ver_ = proto2Ver.get(protocol);
		Bytes.setShort(ver_ == null ? -1 : ver_.intValue(), b, 8 + idLen);
		System.arraycopy(peerHostBytes, 0, b, 10 + idLen, hostLen);
		Bytes.setShort(peerPort, b, 10 + idLen + hostLen);
		System.arraycopy(peerCertsBytes, 0, b, 12 + idLen + hostLen, peerCertsLen);
		System.arraycopy(localCertsBytes, 0, b,
				12 + idLen + hostLen + peerCertsLen, localCertsLen);
		return b;
	}
}