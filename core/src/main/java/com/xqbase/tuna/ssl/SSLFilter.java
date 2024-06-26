package com.xqbase.tuna.ssl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.EventQueue;
import com.xqbase.tuna.util.ByteArrayQueue;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.tuna.util.Expirable;
import com.xqbase.tuna.util.LinkedEntry;
import com.xqbase.tuna.util.TimeoutQueue;

/** An SSL filter which makes a connection secure */
public class SSLFilter extends ConnectionFilter implements Expirable<SSLFilter> {
	/**
	 * Indicates that SSLFilter is created in server mode with
	 * NO client authentication desired.
	 *
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int)
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int, String, int)
	 */
	public static final int SERVER_NO_AUTH = 0;
	/**
	 * Indicates that SSLFilter is created in server mode with
	 * client authentication REQUESTED.
	 *
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int)
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int, String, int)
	 */
	public static final int SERVER_WANT_AUTH = 1;
	/**
	 * Indicates that SSLFilter is created in server mode with
	 * client authentication REQUIRED.
	 *
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int)
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int, String, int)
	 */
	public static final int SERVER_NEED_AUTH = 2;
	/**
	 * Indicates that SSLFilter is created in client mode.
	 *
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int)
	 * @see #SSLFilter(EventQueue, Executor, TimeoutQueue, SSLContext, int, String, int)
	 */
	public static final int CLIENT = 3;

	public static TimeoutQueue<SSLFilter> getTimeoutQueue(int timeout) {
		return new TimeoutQueue<>(sslf -> sslf.disconnectIfClosed(true), timeout);
	}

	private EventQueue eventQueue;
	private Executor executor;
	private TimeoutQueue<SSLFilter> ssltq;
	private SSLEngine ssle;
	private LinkedEntry<SSLFilter> timeoutEntry = null;
	private long expire = 0;
	private boolean enabled = true;
	private int appBBSize;
	private byte[] requestBytes;
	private ByteBuffer requestBB;
	private ConnectionSession session;
	private HandshakeStatus hs = HandshakeStatus.NEED_UNWRAP;
	private ByteArrayQueue baqRecv = new ByteArrayQueue();
	private ByteArrayQueue baqToSend = new ByteArrayQueue();

	/**
	 * Creates an SSLFilter with the given {@link Executor},
	 * {@link SSLContext} and mode
	 *
	 * @param mode - SSL mode, must be {@link #SERVER_NO_AUTH},
	 *			{@link #SERVER_WANT_AUTH}, {@link #SERVER_NEED_AUTH} or {@link #CLIENT}.
	 */
	public SSLFilter(EventQueue eventQueue, Executor executor,
			TimeoutQueue<SSLFilter> ssltq, SSLContext sslc, int mode) {
		this(eventQueue, executor, ssltq, sslc, mode, null, 0);
	}

	/**
	 * Creates an SSLFilter with the given {@link Executor},
	 * {@link SSLContext}, mode and advisory peer information
	 *
	 * @param mode - SSL mode, must be {@link #SERVER_NO_AUTH},
	 *			{@link #SERVER_WANT_AUTH}, {@link #SERVER_NEED_AUTH} or {@link #CLIENT}.
	 * @param peerHost - Advisory peer information.
	 * @param peerPort - Advisory peer information.
	 */
	public SSLFilter(EventQueue eventQueue, Executor executor,
			TimeoutQueue<SSLFilter> ssltq, SSLContext sslc, int mode,
			String peerHost, int peerPort) {
		this.eventQueue = eventQueue;
		this.executor = executor;
		this.ssltq = ssltq;
		if (peerHost == null) {
			ssle = sslc.createSSLEngine();
		} else {
			ssle = sslc.createSSLEngine(peerHost, peerPort);
		}
		ssle.setUseClientMode(mode == CLIENT);
		if (mode == SERVER_NEED_AUTH) {
			ssle.setNeedClientAuth(true);
		}
		if (mode == SERVER_WANT_AUTH) {
			ssle.setWantClientAuth(true);
		}
		appBBSize = ssle.getSession().getApplicationBufferSize();
		requestBytes = new byte[appBBSize];
		requestBB = ByteBuffer.wrap(requestBytes);
	}

	@Override
	public long getExpire() {
		return expire;
	}

	@Override
	public void setExpire(long expire) {
		this.expire = expire;
	}

	@Override
	public void setTimeoutEntry(LinkedEntry<SSLFilter> timeoutEntry) {
		this.timeoutEntry = timeoutEntry;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled) {
			onConnect();
		}
	}

	@Override
	public void send(byte[] b, int off, int len) {
		if (!enabled) {
			super.send(b, off, len);
			return;
		}
		if (hs == HandshakeStatus.FINISHED) {
			disconnectIfClosed(wrap(b, off, len));
		} else {
			baqToSend.add(b, off, len);
		}
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (!enabled) {
			super.onRecv(b, off, len);
			return;
		}
		baqRecv.add(b, off, len);
		if (hs == HandshakeStatus.FINISHED) {
			boolean closed = unwrap();
			onRecv();
			disconnectIfClosed(closed);
		} else if (hs != HandshakeStatus.NEED_TASK) {
			handshake();
		}
	}

	@Override
	public void onConnect(ConnectionSession session_) {
		session = session_;
		if (enabled) {
			onConnect();
		} else {
			super.onConnect(session_);
		}
	}

	@Override
	public void onDisconnect() {
		super.onDisconnect();
		removeTimeout();
	}

	@Override
	public void disconnect() {
		super.disconnect();
		removeTimeout();
	}

	private void disconnectIfClosed(boolean closed) {
		if (closed) {
			disconnect();
			onDisconnect();
		}
	}

	private void removeTimeout() {
		if (timeoutEntry != null) {
			timeoutEntry.remove();
			timeoutEntry = null;
		}
	}

	private void onRecv() {
		if (requestBB.position() > 0) {
			super.onRecv(requestBytes, 0, requestBB.position());
			requestBB.clear();
		}
	}

	/** @return true if closed */
	private boolean unwrap() {
		while (true) {
			SSLEngineResult result;
			try {
				result = unwrapEx();
			} catch (IOException e) {
				return true;
			}
			switch (result.getStatus()) {
			case BUFFER_UNDERFLOW:
				// Wait for next onRecv
				return false;
			case BUFFER_OVERFLOW:
				appBBSize = ssle.getSession().getApplicationBufferSize();
				// Retry unwrapEx
				break;
			case OK:
				if (result.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
					// Continue unwrapEx
					break;
				}
				// TODO TLSv1.3 should return false?
				return true;
			case CLOSED:
				return true;
			default:
				return true;
			}
		}
	}

	private SSLEngineResult unwrapEx() throws IOException {
		if (requestBB.remaining() < appBBSize) {
			byte[] newRequestBytes = new byte[requestBytes.length * 2];
			ByteBuffer bb = ByteBuffer.wrap(newRequestBytes);
			requestBB.flip();
			bb.put(requestBB);
			requestBytes = newRequestBytes;
			requestBB = bb;
		}
		ByteBuffer inNetBB = ByteBuffer.wrap(baqRecv.array(),
				baqRecv.offset(), baqRecv.length());
		int pos = inNetBB.position();
		SSLEngineResult result = ssle.unwrap(inNetBB, requestBB);
		baqRecv.remove(inNetBB.position() - pos);
		return result;
	}

	/** @return true if closed */
	private boolean wrap(byte[] b, int off, int len) {
		try {
			wrapEx(b, off, len);
			return false;
		} catch (IOException e) {
			return true;
		}
	}

	private SSLEngineResult wrapEx(byte[] b, int off, int len) throws IOException {
		ByteBuffer srcBB = ByteBuffer.wrap(b, off, len);
		byte[] outNetBytes = null;
		SSLEngineResult result;
		do {
			int packetBBSize = ssle.getSession().getPacketBufferSize();
			if (outNetBytes == null || outNetBytes.length < packetBBSize) {
				outNetBytes = new byte[packetBBSize];
			}
			ByteBuffer outNetBB = ByteBuffer.wrap(outNetBytes);
			result = ssle.wrap(srcBB, outNetBB);
			if (result.getStatus() != Status.OK) {
				throw new IOException();
			}
			super.send(outNetBytes, 0, outNetBB.position());
		} while (srcBB.remaining() > 0);
		return result;
	}

	private void doTask() {
		Runnable task;
		while ((task = ssle.getDelegatedTask()) != null) {
			task.run();
		}
		eventQueue.invokeLater(() -> {
			hs = ssle.getHandshakeStatus();
			handshake();
		});
	}

	private void onConnect() {
		if (ssltq != null) {
			ssltq.offer(this);
		}
		handshake();
	}

	private void handshake() {
		try {
			handshakeEx();
		} catch (IOException e) {
			disconnectIfClosed(true);
		}
	}

	private void handshakeEx() throws IOException {
		while (hs != HandshakeStatus.FINISHED) {
			switch (hs) {
			case NEED_UNWRAP:
				SSLEngineResult result = unwrapEx();
				hs = result.getHandshakeStatus();
				switch (result.getStatus()) {
				case OK:
					if (hs == HandshakeStatus.NOT_HANDSHAKING) {
						throw new IOException();
					}
					if (hs == HandshakeStatus.NEED_TASK) {
						executor.execute(this::doTask);
						return;
					}
					break;
				case BUFFER_UNDERFLOW: // Wait for next onRecv
					if (hs == HandshakeStatus.NEED_UNWRAP) {
						return;
					}
					break;
				case BUFFER_OVERFLOW:
					appBBSize = ssle.getSession().getApplicationBufferSize();
					break;
				default:
					throw new IOException();
				}
				break;
			case NEED_WRAP:
				result = wrapEx(Bytes.EMPTY_BYTES, 0, 0);
				hs = result.getHandshakeStatus();
				if (hs == HandshakeStatus.NEED_TASK) {
					executor.execute(this::doTask);
					return;
				}
				break;
			case FINISHED:
				break;
			default:
				throw new IOException();
			}
		}
		// hs == HandshakeStatus.FINISHED
		removeTimeout();
		super.onConnect(new SSLConnectionSession(session.getLocalSocketAddress(),
				session.getRemoteSocketAddress(), ssle.getSession()));
		boolean closed = false;
		if (baqRecv.length() > 0) {
			closed = unwrap();
		}
		onRecv();
		if (baqToSend.length() > 0) {
			if (wrap(baqToSend.array(), baqToSend.offset(), baqToSend.length())) {
				closed = true;
			}
		}
		baqToSend = null;
		disconnectIfClosed(closed);
	}
}