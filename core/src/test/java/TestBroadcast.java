import java.io.IOException;
import java.util.HashSet;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.ConnectionHandler;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.ConnectorImpl;

public class TestBroadcast {
	static final ConnectionHandler[] EMPTY_HANDLERS = {};

	public static void main(String[] args) throws IOException {
		// All connected handlers
		HashSet<ConnectionHandler> handlers = new HashSet<>();
		// Initialize a connector
		try (ConnectorImpl connector = new ConnectorImpl()) {
			connector.add(() -> {
				return new Connection() {
					ConnectionHandler handler;

					@Override
					public void setHandler(ConnectionHandler handler) {
						this.handler = handler;
					}

					@Override
					public void onRecv(byte[] b, int off, int len) {
						for (ConnectionHandler handler_ : handlers.toArray(EMPTY_HANDLERS)) {
							// "connection.onDisconnect()" might change "handlers"
							// Broadcast to all connected handlers
							handler_.send(b, off, len);
						}
					}

					@Override
					public void onConnect(ConnectionSession session) {
						handlers.add(handler);
					}

					@Override
					public void onDisconnect() {
						handlers.remove(handler);
					}
				};
			}, 23);
			// Keep the server running for 10 minutes
			connector.postDelayed(connector::interrupt, 600000);
			// "doEvents()" makes the connector working
			connector.doEvents();
		}
	}
}