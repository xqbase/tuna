package com.xqbase.net.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import com.xqbase.net.Connection;
import com.xqbase.net.ServerConnection;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Bytes;
import com.xqbase.util.Streams;

/** A {@link ServerConnection} which provides cross-domain policy service for Adobe Flash. */
public class CrossDomainServer extends ServerConnection {
	private long lastAccessed = 0;
	private File policyFile;

	byte[] policyBytes = Bytes.EMPTY_BYTES;

	void loadPolicy() {
		long now = System.currentTimeMillis();
		if (now < lastAccessed + 60000) {
			return;
		}
		lastAccessed = now;
		try (FileInputStream fin = new FileInputStream(policyFile)) {
			ByteArrayQueue baq = new ByteArrayQueue();
			Streams.copy(fin, baq.getOutputStream());
			policyBytes = new byte[baq.length() + 1];
			baq.remove(policyBytes, 0, policyBytes.length - 1);
			policyBytes[policyBytes.length - 1] = 0;
		} catch (IOException e) {/**/}
	}

	/**
	 * Creates a CrossDomainServer with a given policy file and
	 * the default listening port 843.
	 */
	public CrossDomainServer(File policyFile) throws IOException {
		this(policyFile, 843);
	}

	/** Creates a CrossDomainServer with a given policy file and a given listening port. */
	public CrossDomainServer(File policyFile, int port) throws IOException {
		super(port);
		this.policyFile = policyFile;
	}

	@Override
	protected Connection createConnection() {
		return new Connection() {
			@Override
			protected void onRecv(byte[] b, int off, int len) {
				if (b[len - 1] == 0) {
					loadPolicy();
					send(policyBytes);
					disconnect();
				}
			}
		};
	}
}