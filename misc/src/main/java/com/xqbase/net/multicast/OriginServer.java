package com.xqbase.net.multicast;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Supplier;

import com.xqbase.net.Connection;
import com.xqbase.net.ConnectionHandler;
import com.xqbase.net.ConnectionWrapper;
import com.xqbase.net.ServerConnection;
import com.xqbase.net.packet.PacketFilter;
import com.xqbase.net.util.Bytes;

class VirtualHandler implements ConnectionHandler {
	private String localAddr, remoteAddr;
	private int localPort, remotePort;

	EdgeConnection edge;
	int connId;

	VirtualHandler(EdgeConnection edge, int connId, byte[] addr) {
		this.edge = edge;
		this.connId = connId;
		if (addr.length == 12 || addr.length == 36) {
			int addrLen = addr.length / 2 - 2;
			try {
				localAddr = InetAddress.getByAddress(Bytes.sub(addr, 0, addrLen)).getHostAddress();
				remoteAddr = InetAddress.getByAddress(Bytes.sub(addr, addrLen + 2, addrLen)).getHostAddress();
			} catch (IOException e) {
				localAddr = remoteAddr = InetAddress.getLoopbackAddress().getHostAddress();
			}
			localPort = Bytes.toShort(Bytes.sub(addr, addrLen, 2)) & 0xFFFF;
			remotePort = Bytes.toShort(Bytes.sub(addr, addrLen * 2 + 2, 2)) & 0xFFFF;
		} else {
			localAddr = remoteAddr = InetAddress.getLoopbackAddress().getHostAddress();
		}
	}

	@Override
	public void send(byte[] b, int off, int len) {
		byte[] head = new MulticastPacket(connId,
				MulticastPacket.ORIGIN_DATA, 0, len).getHead();
		edge.handler.send(Bytes.add(head, 0, head.length, b, off, len));
	}

	@Override
	public void disconnect() {
		Connection connection = edge.connMap.remove(Integer.valueOf(connId));
		if (connection != null) {
			edge.origin.virtualMap.remove(connection);
		}
		edge.handler.send(new MulticastPacket(connId,
				MulticastPacket.ORIGIN_DISCONNECT, 0, 0).getHead());
	}

	@Override
	public void blockRecv(boolean blocked) {
		// Nothing to do
	}

	@Override
	public String getLocalAddr() {
		return localAddr;
	}

	@Override
	public int getLocalPort() {
		return localPort;
	}

	@Override
	public String getRemoteAddr() {
		return remoteAddr;
	}

	@Override
	public int getRemotePort() {
		return remotePort;
	}

	@Override
	public int getQueueSize() {
		return 0;
	}

	@Override
	public long getBytesRecv() {
		return 0;
	}

	@Override
	public long getBytesSent() {
		return 0;
	}

	@Override
	public Closeable postAtTime(Runnable runnable, long uptime) {
		return edge.handler.postAtTime(runnable, uptime);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		edge.handler.invokeLater(runnable);
	}

	@Override
	public void execute(Runnable command) {
		edge.handler.execute(command);
	}
}

class EdgeConnection implements Connection {
	HashMap<Integer, Connection> connMap = new HashMap<>();
	ConnectionHandler handler;
	OriginServer origin;

	EdgeConnection(OriginServer origin) {
		this.origin = origin;
		appendFilter(new PacketFilter(MulticastPacket.getParser()));
	}

	@Override
	public void setHandler(ConnectionHandler handler) {
		this.handler = handler;
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		MulticastPacket packet = new MulticastPacket(b, off, len);
		int connId = packet.connId;
		int command = packet.command;
		if (command == MulticastPacket.EDGE_PING) {
			handler.send(new MulticastPacket(0, MulticastPacket.ORIGIN_PONG, 0, 0).getHead());
		} else if (command == MulticastPacket.EDGE_CONNECT) {
			if (connMap.containsKey(Integer.valueOf(connId))) {
				// throw new PacketException("#" + connId + " Already Exists");
				handler.disconnect();
				onDisconnect();
				return;
			}
			Connection connection = origin.getVirtual(null);
			for (Supplier<? extends ConnectionWrapper> serverFilter : origin.virtualServerFilters) {
				connection = connection.appendFilter(serverFilter.get());
			}
			VirtualHandler virtualHandler = new VirtualHandler(this,
					connId, Bytes.sub(b, off + 16, len - 16));
			connection.setHandler(virtualHandler);
			connMap.put(Integer.valueOf(connId), connection);
			origin.virtualMap.put(connection, virtualHandler);
			connection.onConnect();
		} else {
			Connection connection = connMap.get(Integer.valueOf(connId));
			if (connection == null) {
				return;
			}
			if (command == MulticastPacket.EDGE_DATA) {
				if (16 + packet.size > len) {
					// throw new PacketException("Wrong Packet Size");
					handler.disconnect();
					onDisconnect();
					return;
				}
				connection.onRecv(b, off + 16, packet.size);
			} else { // MulticastPacket.EDGE_DISCONNECT
				connMap.remove(Integer.valueOf(connId));
				origin.virtualMap.remove(connection);
				handler.send(new MulticastPacket(connId,
						MulticastPacket.ORIGIN_CLOSE, 0, 0).getHead());
				connection.onDisconnect();
			}
		}
	}

	@Override
	public void onDisconnect() {
		for (Connection connection : connMap.values().toArray(new Connection[0])) {
			// "conn.onDisconnect()" might change "connMap"
			connection.onDisconnect();
			origin.virtualMap.remove(connection);
		}
	}
}

class MulticastHandler extends ConnectionWrapper {
	private OriginServer origin;
	private Collection<? extends Connection> connections;

	MulticastHandler(OriginServer origin, Collection<? extends Connection> connections) {
		this.origin = origin;
		this.connections = connections;
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
		for (Connection connection : connections) {
			// nothing can change "connections", so the iteration is safe
			VirtualHandler handler = origin.virtualMap.get(connection);
			if (handler == null) {
				continue;
			}
			EdgeConnection edge = handler.edge;
			ArrayList<Integer> connList = connListMap.get(edge);
			if (connList == null) {
				connList = new ArrayList<>();
				connListMap.put(edge, connList);
			}
			connList.add(Integer.valueOf(handler.connId));
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
				byte[] head = new MulticastPacket(0,
						MulticastPacket.ORIGIN_MULTICAST, numConns, len).getHead();
				edge.handler.send(Bytes.add(head, connListBytes, Bytes.sub(b, off, len)));
				numConnsToSend -= numConns;
				numConnsSent += numConns;
			}
		}
	}
}

/**
 * An origin server can manage a large number of virtual {@link Connection}s
 * via several {@link EdgeServer}s.
 * @see EdgeServer
 */
public abstract class OriginServer implements ServerConnection {
	/**
	 * @param key non-null for a multicast connection
	 * @return A virtual {@link Connection} that belongs to the origin server. 
	 */
	protected abstract Connection getVirtual(Object key);

	LinkedHashMap<Connection, VirtualHandler> virtualMap = new LinkedHashMap<>();

	@Override
	public Connection get() {
		return new EdgeConnection(this);
	}

	/** @return A set of all virtual connections. */
	public Set<Connection> getVirtuals() {
		return virtualMap.keySet();
	}

	ArrayList<Supplier<? extends ConnectionWrapper>> virtualServerFilters = new ArrayList<>();

	/** Adds a {@link ConnectionWrapper} as a filter into the network end of each virtual connection */
	public void appendVirtualFilter(Supplier<? extends ConnectionWrapper> serverFilter) {
		virtualServerFilters.add(serverFilter);
	}

	/**
	 * The broadcasting to a large number of virtual connections can be done via a multicast
	 * connection, which can save the network bandwidth by the multicast approach.<p>
	 * TODO For detailed usage, see {@link TestMulticast}
	 * @param connections - A large number of virtual connections where data is broadcasted.
	 */
	public Object getMulticast(Collection<? extends Connection> connections) {
		Object key = new Object();
		Connection connection = getVirtual(key);
		for (Supplier<? extends ConnectionWrapper> serverFilter : virtualServerFilters) {
			connection = connection.appendFilter(serverFilter.get());
		}
		connection.setHandler(new MulticastHandler(this, connections));
		return key;
	}

	// TODO Check Edge Timeout
}