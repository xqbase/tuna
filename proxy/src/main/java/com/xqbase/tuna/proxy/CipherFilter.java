package com.xqbase.tuna.proxy;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import com.xqbase.tuna.ConnectionFilter;

public class CipherFilter extends ConnectionFilter {
	private static final String ALG = "RC4";

	private Cipher encrypt, decrypt;
	private byte[] buffer;

	public CipherFilter(String key, byte[] buffer) {
		SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), ALG);
		try {
			encrypt = Cipher.getInstance(ALG);
			encrypt.init(Cipher.ENCRYPT_MODE, keySpec);
			decrypt = Cipher.getInstance(ALG);
			decrypt.init(Cipher.DECRYPT_MODE, keySpec);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
		this.buffer = buffer;
	}

	private void update(Cipher cipher, byte[] b, int off, int len) {
		try {
			int bufferLen = cipher.update(b, off, len, buffer, 0);
			if (len != bufferLen) {
				throw new RuntimeException("encryption/decription error, expected bytes: " +
						len + ", actual bytes: " + bufferLen);
			}
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void send(byte[] b, int off, int len) {
		update(encrypt, b, off, len);
		super.send(buffer, 0, len);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		update(decrypt, b, off, len);
		super.onRecv(buffer, 0, len);
	}
}