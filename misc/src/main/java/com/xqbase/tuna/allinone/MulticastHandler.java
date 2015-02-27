package com.xqbase.tuna.allinone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;

import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionWrapper;
import com.xqbase.tuna.util.Bytes;

/**
 * The broadcasting to a large number of virtual connections can be done via a multicast
 * connection, which can save the network bandwidth by the multicast approach.<p>
 * For detailed usage, see {@link TestMulticast} from github.com
 */
public class MulticastHandler extends ConnectionWrapper {
	public static boolean isMulticast(ConnectionHandler handler) {
		ConnectionHandler handler_ = handler;
		while (!(handler_ instanceof MulticastHandler)) {
			if (!(handler_ instanceof ConnectionWrapper)) {
				return false;
			}
			handler_ = ((ConnectionWrapper) handler_).getHandler();
		}
		return true;
	}

	private Collection<ConnectionHandler> handlers;

	/** @param handlers - A large number of handlers to broadcast. */
	public MulticastHandler(Collection<ConnectionHandler> handlers) {
		this.handlers = handlers;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		if (len > 64000) {
			send(b, off, 64000);
			send(b, off + 64000, len - 64000);
			return;
		}
		int maxNumConns = (65535 - 16 - len) / 4;
		HashMap<EdgeConnection, ArrayList<Integer>> connListMap = new HashMap<>();
		// "connections.iterator()" is called
		for (ConnectionHandler handler : handlers) {
			while (!(handler instanceof VirtualHandler)) {
				if (!(handler instanceof ConnectionWrapper)) {
					handler = null;
					break;
				}
				handler = ((ConnectionWrapper) handler).getHandler();
			}
			if (handler == null) {
				continue;
			}
			VirtualHandler virtual = ((VirtualHandler) handler);
			EdgeConnection edge = virtual.edge;
			ArrayList<Integer> connList = connListMap.get(edge);
			if (connList == null) {
				connList = new ArrayList<>();
				connListMap.put(edge, connList);
			}
			connList.add(Integer.valueOf(virtual.connId));
		}
		for (Entry<EdgeConnection, ArrayList<Integer>> entry : connListMap.entrySet()) {
			EdgeConnection edge = entry.getKey();
			ArrayList<Integer> connList = entry.getValue();
			int numConnsToSend = connList.size();
			int numConnsSent = 0;
			while (numConnsToSend > 0) {
				int numConns = Math.min(numConnsToSend, maxNumConns);
				byte[] connListBytes = new byte[numConns * 4];
				for (int i = 0; i < numConns; i ++) {
					Bytes.setInt(connList.get(numConnsSent + i).intValue(),
							connListBytes, i * 4);
				}
				byte[] head = new AllInOnePacket(0,
						AllInOnePacket.ORIGIN_MULTICAST, numConns, len).getHead();
				edge.handler.send(Bytes.add(head, connListBytes, Bytes.sub(b, off, len)));
				numConnsToSend -= numConns;
				numConnsSent += numConns;
			}
		}
	}

	@Override
	public String getLocalAddr() {
		return "0.0.0.0";
	}

	@Override
	public int getLocalPort() {
		return 0;
	}

	@Override
	public String getRemoteAddr() {
		return "0.0.0.0";
	}

	@Override
	public int getRemotePort() {
		return 0;
	}
}