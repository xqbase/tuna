package com.xqbase.tuna.proxy;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import com.xqbase.tuna.ConnectorImpl;
import com.xqbase.tuna.ServerConnection;
import com.xqbase.tuna.mux.MuxContext;
import com.xqbase.tuna.mux.OriginServer;
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.tuna.util.Bytes;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

public class TunaProxy {
	private static final List<String> LOG_VALUE = Arrays.asList("debug", "verbose");
	private static final List<String> FORWARDED_VALUE =
			Arrays.asList("off", "delete", "truncate", "on");

	private static SSLContext getSSLContext(String dn, long expire)
			throws IOException, GeneralSecurityException {
		KeyManager[] kms;
		if (dn == null) {
			kms = SSLManagers.DEFAULT_KEY_MANAGERS;
		} else {
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, null);
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(1024);
			KeyPair keyPair = kpg.genKeyPair();
			long now = System.currentTimeMillis();
			X509CertInfo info = new X509CertInfo();
			info.set("version", new CertificateVersion(2));
			info.set("serialNumber", new CertificateSerialNumber(0));
			info.set("algorithmID",
					new CertificateAlgorithmId(AlgorithmId.get("SHA1withRSA")));
			X500Name x500Name = new X500Name(dn);
			info.set("subject", x500Name);
			info.set("key", new CertificateX509Key(keyPair.getPublic()));
			info.set("validity", new CertificateValidity(new
					Date(now), new Date(now + expire)));
			info.set("issuer", x500Name);
			X509CertImpl cert = new X509CertImpl(info);
			cert.sign(keyPair.getPrivate(), "SHA1withRSA");
			ks.setKeyEntry("", keyPair.getPrivate(), new char[0],
					new X509Certificate[] {cert});
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, new char[0]);
			kms = kmf.getKeyManagers();
		}
		SSLContext sslc = SSLContext.getInstance("TLS");
		sslc.init(kms, SSLManagers.DEFAULT_TRUST_MANAGERS, null);
		return sslc;
	}

	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		System.setProperty("java.util.logging.SimpleFormatter.format",
				"%1$tY-%1$tm-%1$td %1$tk:%1$tM:%1$tS.%1$tL %2$s%n%4$s: %5$s%6$s%n");
		Logger logger = Log.getAndSet(Conf.openLogger("TunaProxy.", 16777216, 10));
		Properties p = Conf.load("TunaProxy");
		String host = p.getProperty("host");
		host = host == null || host.isEmpty() ? "0.0.0.0" : host;
		int port = Numbers.parseInt(p.getProperty("port"), 3128, 1, 65535);
		boolean authEnabled = Conf.getBoolean(p.getProperty("auth"), false);
		boolean lookupEnabled = Conf.getBoolean(p.getProperty("lookup"), false);
		String realm = p.getProperty("realm");
		boolean enableReverse = Conf.getBoolean(p.getProperty("reverse"), false);
		int keepAlive = Numbers.parseInt(p.getProperty("keep_alive"), (int) Time.MINUTE);
		String forwardedValue = p.getProperty("forwarded");
		int forwardedType = forwardedValue == null ? 0 :
				FORWARDED_VALUE.indexOf(forwardedValue.toLowerCase()) + 1;
		String logValue = Conf.DEBUG ? "verbose" : p.getProperty("log");
		int logLevel = logValue == null ? 0 : LOG_VALUE.indexOf(logValue.toLowerCase()) + 1;

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);

			ProxyServer server = new ProxyServer(connector, connector, connector);
			if (authEnabled) {
				HashMap<String, String> authMap = new HashMap<>();
				server.setAuth((t, u) -> {
					if (t == null) {
						return false;
					}
					String password = authMap.get(t);
					return password != null && password.equals(u);
				});
				connector.scheduleDelayed(() -> {
					authMap.clear();
					Conf.load("Auth").forEach((k, v) ->
							authMap.put((String) k, (String) v));
				}, 0, 10000);
			}
			if (lookupEnabled) {
				HashMap<String, String> lookupMap = new HashMap<>();
				server.setLookup(lookupMap::get);
				connector.scheduleDelayed(() -> {
					lookupMap.clear();
					Conf.load("Lookup").forEach((k, v) ->
							lookupMap.put((String) k, (String) v));
				}, 0, 10000);
			}
			server.setRealm(realm);
			server.setEnableReverse(enableReverse);
			server.setKeepAlive(keepAlive);
			server.setForwardedType(forwardedType);
			server.setLogLevel(logLevel);
			connector.scheduleDelayed(server, 10000, 10000);

			ServerConnection server_;
			if (Conf.getBoolean(p.getProperty("mux"), false)) {
				String authPhrase = p.getProperty("mux.auth_phrase");
				Predicate<byte[]> muxAuth;
				if (authPhrase == null || authPhrase.isEmpty()) {
					muxAuth = t -> true;
				} else {
					byte[] authPhrase_ = authPhrase.getBytes();
					muxAuth = t -> t != null && Bytes.equals(t, authPhrase_);
				}
				int queueLimit = Numbers.parseInt(p.
						getProperty("mux.queue_limit"), 1048576);
				server_ = new OriginServer(server, new MuxContext(connector,
						muxAuth, queueLimit, logLevel));
			} else {
				server_ = server;
			}

			if (Conf.getBoolean(p.getProperty("ssl"), false)) {
				SSLContext sslcServer = getSSLContext("CN=localhost", Time.WEEK * 520);
				connector.add(server_.appendFilter(() -> new SSLFilter(connector,
						connector, server.ssltq, sslcServer,
						SSLFilter.SERVER_NO_AUTH)), host, port);
			} else {
				connector.add(server_, host, port);
			}
			Log.i("Tuna Proxy Started on " + host + ":" + port);
			if (logLevel >= ProxyConnection.LOG_VERBOSE) {
				connector.setOnBeginSelect(timeout -> {
					Log.v("onBeginSelect(" + timeout + ")");
				});
				connector.setOnEndSelect(keySize -> {
					Log.v("onEndSelect(" + keySize + ")");
				});
			}
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("Tuna Proxy Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}