package com.xqbase.net.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.ServerListener;
import com.xqbase.net.util.ByteArrayQueue;
import com.xqbase.net.util.Bytes;

/** A {@link ServerListener} which provides cross-domain policy service for Adobe Flash. */
public class CrossDomainServer implements ServerListener {
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
			byte[] buffer = new byte[2048];
			int bytesRead;
			while ((bytesRead = fin.read(buffer)) > 0) {
				baq.add(buffer, 0, bytesRead);
			}
			policyBytes = new byte[baq.length() + 1];
			baq.remove(policyBytes, 0, policyBytes.length - 1);
			policyBytes[policyBytes.length - 1] = 0;
		} catch (IOException e) {/**/}
	}

	/** Creates a CrossDomainServer with a given policy file. */
	public CrossDomainServer(File policyFile) {
		this.policyFile = policyFile;
	}

	@Override
	public Listener get() {
		return new Listener() {
			private Handler handler;

			@Override
			public void setHandler(Handler handler) {
				this.handler = handler;
			}

			@Override
			public void onRecv(byte[] b, int off, int len) {
				if (b[len - 1] == 0) {
					loadPolicy();
					handler.send(policyBytes);
					handler.disconnect();
				}
			}
		};
	}
}