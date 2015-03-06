package com.xqbase.tuna.mux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map.Entry;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;

/**
 * The broadcasting to a large number of virtual connections can be done via a multicast
 * connection, which can save the network bandwidth by the multicast approach.<p>
 * For detailed usage, see {@link com.xqbase.tuna.mux.TestMulticast} from github.com
 */
public class MulticastHandler implements ConnectionHandler {
	private static final int HEAD_SIZE = MuxPacket.HEAD_SIZE;

	public static boolean isMulticast(ConnectionHandler handler) {
		ConnectionHandler handler_ = handler;
		while (!(handler_ instanceof MulticastHandler)) {
			if (!(handler_ instanceof ConnectionFilter)) {
				return false;
			}
			handler_ = ((ConnectionFilter) handler_).getHandler();
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
		int maxNumConns = (65535 - HEAD_SIZE - len) / 2;
		HashMap<ConnectionHandler, ArrayList<Integer>> connListMap = new HashMap<>();
		// "connections.iterator()" is called
		for (ConnectionHandler handler : handlers) {
			while (!(handler instanceof VirtualHandler)) {
				if (!(handler instanceof ConnectionFilter)) {
					handler = null;
					break;
				}
				handler = ((ConnectionFilter) handler).getHandler();
			}
			if (handler == null) {
				continue;
			}
			VirtualHandler virtual = ((VirtualHandler) handler);
			ConnectionHandler muxHandler = virtual.getMuxHandler();
			ArrayList<Integer> connList = connListMap.get(muxHandler);
			if (connList == null) {
				connList = new ArrayList<>();
				connListMap.put(muxHandler, connList);
			}
			connList.add(Integer.valueOf(virtual.getConnectionID()));
		}
		for (Entry<ConnectionHandler, ArrayList<Integer>> entry : connListMap.entrySet()) {
			ConnectionHandler muxHandler = entry.getKey();
			ArrayList<Integer> connList = entry.getValue();
			int numConnsToSend = connList.size();
			int numConnsSent = 0;
			while (numConnsToSend > 0) {
				int numConns = Math.min(numConnsToSend, maxNumConns);
				byte[] bb = new byte[HEAD_SIZE + numConns * 2 + len];
				for (int i = 0; i < numConns; i ++) {
					Bytes.setShort(connList.get(numConnsSent + i).intValue(),
							bb, HEAD_SIZE + i * 2);
				}
				System.arraycopy(b, off, bb, HEAD_SIZE + numConns * 2, len);
				MuxPacket.send(muxHandler, bb, MuxPacket.HANDLER_MULTICAST, numConns);
				numConnsToSend -= numConns;
				numConnsSent += numConns;
			}
		}
	}

	// TODO multicast "setBufferSize" and "disconnect" ? 
	@Override
	public void setBufferSize(int bufferSize) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void disconnect() {
		throw new UnsupportedOperationException();
	}
}