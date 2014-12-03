package com.xqbase.net.misc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

import com.xqbase.net.Connector;
import com.xqbase.net.FilterFactory;
import com.xqbase.net.Handler;
import com.xqbase.net.Listener;
import com.xqbase.net.ListenerFactory;

class PeerListener implements Listener {
	PeerListener peer;
	Handler handler;

	PeerListener(PeerListener peer) {
		this.peer = peer;
	}

	@Override
	public void setHandler(Handler handler) {
		this.handler = handler;
	}

	@Override
	public void onSend(boolean queued) {
		if (peer != null) {
			peer.handler.blockRecv(queued);
		}
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		if (peer != null) {
			peer.handler.send(b, off, len);
		}
	}

	@Override
	public void onDisconnect() {
		if (peer != null) {
			peer.peer = null;
			peer.handler.disconnect();
		}
	}
}

class ForwardListener extends PeerListener {
	private ForwardServer forward;

	ForwardListener(ForwardServer forward) {
		super(null);
		this.forward = forward;
	}

	@Override
	public void onConnect() {
		peer = new PeerListener(this);
		Listener listener = peer;
		for (FilterFactory filterFactory : forward.filterFactories) {
			listener = listener.appendFilter(filterFactory.createFilter());
		}
		try {
			forward.connector.connect(listener, forward.remote);
		} catch (IOException e) {
			peer = null;
			handler.disconnect();
		}
	}
}

/** A port redirecting server. */
public class ForwardServer implements ListenerFactory {
	Connector connector;
	InetSocketAddress remote;
	ArrayList<FilterFactory> filterFactories = new ArrayList<>();

	@Override
	public Listener onAccept() {
		return new ForwardListener(this);
	}

	/**
	 * Creates a ForwardServer.
	 * @param connector - A connector which remote connections registered to.
	 * @param remoteHost - The remote (redirected) host.
	 * @param remotePort - The remote (redirected) port.
	 * @throws IOException If an I/O error occurs when opening the port.
	 */
	public ForwardServer(Connector connector, String remoteHost, int remotePort) throws IOException {
		this.connector = connector;
		remote = new InetSocketAddress(remoteHost, remotePort);
	}

	/**
	 * Creates a ForwardServer.
	 * @param connector - A connector which remote connections registered to.
	 * @param remote - The remote (redirected) address.
	 */
	public ForwardServer(Connector connector, InetSocketAddress remote) {
		this.connector = connector;
		this.remote = remote;
	}

	/** @return A list of {@link FilterFactory}s applied to remote connections. */
	public void appendRemoteFilter(FilterFactory filterFactory) {
		filterFactories.add(filterFactory);
	}
}