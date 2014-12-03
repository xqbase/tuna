package com.xqbase.net.misc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.ListenerFactory;
import com.xqbase.util.ByteArrayQueue;
import com.xqbase.util.Bytes;
import com.xqbase.util.Streams;

/** A {@link ListenerFactory} which provides cross-domain policy service for Adobe Flash. */
public class CrossDomainServer implements ListenerFactory {
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

	/** Creates a CrossDomainServer with a given policy file. */
	public CrossDomainServer(File policyFile) {
		this.policyFile = policyFile;
	}

	@Override
	public Listener onAccept() {
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