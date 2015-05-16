package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;

import com.xqbase.tuna.Connection;
import com.xqbase.tuna.Connector;
import com.xqbase.tuna.EventQueue;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.http.HttpPacket;
import com.xqbase.tuna.http.HttpStatus;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.function.BiConsumerEx;

public class ProxyContext implements Connector, EventQueue, Executor {
	public static final int
			FORWARDED_TRANSPARENT = 0,
			FORWARDED_DELETE = 1,
			FORWARDED_OFF = 2,
			FORWARDED_TRUNCATE = 3,
			FORWARDED_ON = 4;
	public static final int
			LOG_NONE = 0,
			LOG_DEBUG = 1,
			LOG_VERBOSE = 2;
	private static final String ERROR_PAGE_FORMAT = "<!DOCTYPE html><html>" +
			"<head><title>%d %s</title></head>" +
			"<body><center><h1>%d %s</h1></center><hr><center>Tuna Proxy/0.1.0</center></body>" +
			"</html>";

	private static HashMap<Integer, String> reasonMap = new HashMap<>();
	private static HashMap<Integer, byte[]> errorPageMap = new HashMap<>();

	private static SSLContext defaultSSLContext;

	static {
		try {
			for (Field field : HttpStatus.class.getFields()) {
				String name = field.getName();
				if (!name.startsWith("SC_") || field.getModifiers() !=
						Modifier.PUBLIC + Modifier.STATIC + Modifier.FINAL) {
					continue;
				}
				Integer status = (Integer) field.get(null);
				StringBuilder sb = new StringBuilder();
				for (String s : name.substring(3).split("_")) {
					if (s.equals("HTTP")) {
						sb.append(" HTTP");
					} else if (!s.isEmpty()) {
						sb.append(' ').append(s.charAt(0)).
								append(s.substring(1).toLowerCase());
					}
				}
				String reason = sb.substring(1);
				reasonMap.put(status, reason);
				errorPageMap.put(status, String.format(ERROR_PAGE_FORMAT,
						status, reason, status, reason).getBytes());
			}
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
		try {
			defaultSSLContext = SSLContext.getInstance("TLS");
			defaultSSLContext.init(SSLManagers.DEFAULT_KEY_MANAGERS,
					SSLManagers.DEFAULT_TRUST_MANAGERS, null);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	public static String getReason(int status) {
		String reason = reasonMap.get(Integer.valueOf(status));
		return reason == null ? "" + status : reason;
	}

	public static byte[] getDefaultErrorPage(int status) {
		byte[] errorPage = errorPageMap.get(Integer.valueOf(status));
		return errorPage == null ? Bytes.EMPTY_BYTES : errorPage;
	}

	private Connector connector;
	private EventQueue eventQueue;
	private Executor executor;
	private SSLContext sslc = defaultSSLContext;
	private BiPredicate<String, String> auth = (t, u) -> true;
	private UnaryOperator<String> lookup = t -> t;
	private BiConsumerEx<Map<String, Object>, HttpPacket, RequestException>
			onRequest = (t, u) -> {/**/};
	private BiConsumer<Map<String, Object>, HttpPacket> onResponse = (t, u) -> {/**/};
	private Consumer<Map<String, Object>> onComplete = t -> {/**/};
	private IntFunction<byte[]> errorPages = ProxyContext::getDefaultErrorPage;
	private String realm = null;
	private boolean enableReverse = false;
	private int forwardedType = FORWARDED_TRANSPARENT, logLevel = LOG_NONE;

	public ProxyContext(Connector connector, EventQueue eventQueue, Executor executor) {
		this.connector = connector;
		this.eventQueue = eventQueue;
		this.executor = executor;
	}

	@Override
	public Closeable add(ServerConnection serverConnection,
			InetSocketAddress socketAddress) throws IOException {
		return connector.add(serverConnection, socketAddress);
	}

	@Override
	public void connect(Connection connection,
			InetSocketAddress socketAddress) throws IOException {
		connector.connect(connection, socketAddress);
	}

	@Override
	public void invokeLater(Runnable runnable) {
		eventQueue.invokeLater(runnable);
	}

	@Override
	public void execute(Runnable command) {
		executor.execute(command);
	}

	public void setSSLContext(SSLContext sslc) {
		this.sslc = sslc;
	}

	public void setAuth(BiPredicate<String, String> auth) {
		this.auth = auth;
	}

	public void setLookup(UnaryOperator<String> lookup) {
		this.lookup = lookup;
	}

	public void setOnRequest(BiConsumerEx<Map<String, Object>,
			HttpPacket, RequestException> onRequest) {
		this.onRequest = onRequest;
	}

	public void setOnResponse(BiConsumer<Map<String, Object>, HttpPacket> onResponse) {
		this.onResponse = onResponse;
	}

	public void setOnComplete(Consumer<Map<String, Object>> onComplete) {
		this.onComplete = onComplete;
	}

	public void setErrorPages(IntFunction<byte[]> errorPages) {
		this.errorPages = errorPages;
	}

	public void setRealm(String realm) {
		this.realm = realm;
	}

	public void setEnableReverse(boolean enableReverse) {
		this.enableReverse = enableReverse;
	}

	public void setForwardedType(int forwardedType) {
		this.forwardedType = forwardedType;
	}

	public void setLogLevel(int logLevel) {
		this.logLevel = logLevel;
	}

	public SSLContext getSSLContext() {
		return sslc;
	}

	public boolean auth(String username, String password) {
		return auth.test(username, password);
	}

	public String lookup(String host) {
		return lookup.apply(host);
	}

	public void onRequest(Map<String, Object> bindings,
			HttpPacket request) throws RequestException {
		onRequest.accept(bindings, request);
	}

	public void onResponse(Map<String, Object> bindings, HttpPacket response) {
		onResponse.accept(bindings, response);
	}

	public void onComplete(Map<String, Object> bindings) {
		onComplete.accept(bindings);
	}

	public byte[] getErrorPage(int status) {
		return errorPages.apply(status);
	}

	public String getRealm() {
		return realm;
	}

	public boolean isEnableReverse() {
		return enableReverse;
	}

	public int getForwardedType() {
		return forwardedType;
	}

	public int getLogLevel() {
		return logLevel;
	}
}