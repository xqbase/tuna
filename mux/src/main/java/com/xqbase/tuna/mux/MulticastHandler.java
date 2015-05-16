package com.xqbase.tuna.mux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.util.Bytes;

/**
 * The broadcasting to a large number of virtual connections can be done via a multicast
 * connection, which can save the network bandwidth by the multicast approach.<p>
 * For detailed usage, see <code>com.xqbase.tuna.mux.TestMulticast</code> in test
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
			while (!(handler instanceof VirtualConnection)) {
				if (!(handler instanceof ConnectionFilter)) {
					handler = null;
					break;
				}
				handler = ((ConnectionFilter) handler).getHandler();
			}
			if (handler == null) {
				continue;
			}
			VirtualConnection virtual = ((VirtualConnection) handler);
			ConnectionHandler muxHandler = virtual.mux.handler;
			ArrayList<Integer> connList = connListMap.get(muxHandler);
			if (connList == null) {
				connList = new ArrayList<>();
				connListMap.put(muxHandler, connList);
			}
			connList.add(Integer.valueOf(virtual.cid));
		}
		connListMap.forEach((muxHandler, connList) -> {
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
		});
	}

	// unable to multicast "setBufferSize" and "disconnect" yet 
	@Override
	public void setBufferSize(int bufferSize) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void disconnect() {
		throw new UnsupportedOperationException();
	}
}