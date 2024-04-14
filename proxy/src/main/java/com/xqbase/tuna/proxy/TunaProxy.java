package com.xqbase.tuna.proxy;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
import com.xqbase.tuna.ssl.SSLFilter;
import com.xqbase.tuna.ssl.SSLManagers;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Runnables;
import com.xqbase.util.Service;
import com.xqbase.util.Strings;
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
		String binds = p.getProperty("binds", "");
		boolean authEnabled = Conf.getBoolean(p.getProperty("auth"), false);
		boolean lookupEnabled = Conf.getBoolean(p.getProperty("lookup"), false);
		String cipherKey = p.getProperty("cipher_key", "changeit");
		String proxyChain = p.getProperty("proxy_chain");
		String proxyAuth = p.getProperty("proxy_auth");
		String realm = p.getProperty("realm");
		boolean enableReverse = Conf.getBoolean(p.getProperty("reverse"), false);
		int keepAlive = Numbers.parseInt(p.getProperty("keep_alive"), (int) Time.MINUTE);
		String forwardedValue = p.getProperty("forwarded");
		int forwardedType = forwardedValue == null ? 0 :
				FORWARDED_VALUE.indexOf(forwardedValue.toLowerCase()) + 1;
		String logValue = Conf.DEBUG ? "verbose" : p.getProperty("log");
		int logLevel = logValue == null ? 0 : LOG_VALUE.indexOf(logValue.toLowerCase()) + 1;

		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.register(connector::interrupt);

			ProxyServer server = new ProxyServer(connector, connector, connector);
			if (authEnabled) {
				Map<String, String> authMap = new HashMap<>();
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
				Map<String, String> lookupMap = new HashMap<>();
				server.setLookup(lookupMap::get);
				connector.scheduleDelayed(() -> {
					lookupMap.clear();
					Conf.load("Lookup").forEach((k, v) ->
							lookupMap.put((String) k, (String) v));
				}, 0, 10000);
			}
			if (proxyChain != null) {
				HashSet<String> domains = new HashSet<>();
				ArrayList<String> suffixes = new ArrayList<>();
				connector.scheduleDelayed(Runnables.wrap(() -> {
					domains.clear();
					String filename = Conf.
							getAbsolutePath("conf/ProxyDomains.txt");
					try (BufferedReader in = new BufferedReader(new
							FileReader(filename))) {
						String s;
						while ((s = in.readLine()) != null) {
							if (!Strings.isBlank(s) && !s.startsWith("#")) {
								domains.add(s.toLowerCase());
							}
						}
					} catch (IOException e) {
						Log.w(filename + ": " + e.getMessage()); // Ignored
					}
					suffixes.clear();
					for (String domain : domains) {
						suffixes.add("." + domain);
					}
				}), 0, 10000);
				server.setOnRequest((connection, packet) -> {
					String host;
					if (packet.getMethod().toUpperCase().equals("CONNECT")) {
						String uri = packet.getUri();
						int colon = uri.indexOf(':');
						host = colon < 0 ? uri : uri.substring(0, colon);
					} else {
						URL url;
						try {
							url = new URL(packet.getUri());
						} catch (IOException e) {
							if (logLevel >= ProxyConnection.LOG_DEBUG) {
								Log.d("Invalid URI: " + packet.getUri());
							}
							return;
						}
						host = url.getHost();
					}
					host = host.toLowerCase();
					boolean chained = domains.contains(host);
					if (!chained) {
						for (String suffix : suffixes) {
							if (host.endsWith(suffix)) {
								chained = true;
								break;
							}
						}
					}
					if (chained) {
						connection.setAttribute(ProxyConnection.
								PROXY_CHAIN_KEY, proxyChain);
						if (proxyAuth != null) {
							connection.setAttribute(ProxyConnection.
									PROXY_AUTH_KEY, proxyAuth);
						}
					}
				});
			}
			server.setRealm(realm);
			server.setEnableReverse(enableReverse);
			server.setKeepAlive(keepAlive);
			server.setForwardedType(forwardedType);
			server.setLogLevel(logLevel);
			connector.scheduleDelayed(server, 10000, 10000);

			for (String bind_ : binds.split("[,;]")) {
				if (Strings.isBlank(bind_)) {
					continue;
				}
				String bind = bind_.trim().toLowerCase();
				boolean secure = false, cipher = false;
				if (bind.endsWith("s")) {
					bind = bind.substring(0, bind.length() - 1);
					secure = true;
				} else if (bind.endsWith("c")) {
					bind = bind.substring(0, bind.length() - 1);
					cipher = true;
				}
				int colon = bind.indexOf(':');
				InetSocketAddress addr;
				if (colon < 0) {
					addr = new InetSocketAddress(Numbers.parseInt(bind));
				} else {
					addr = new InetSocketAddress(bind.substring(0, colon),
							Numbers.parseInt(bind.substring(colon + 1)));
				}
				if (secure) {
					SSLContext sslcServer = getSSLContext("CN=localhost", Time.WEEK * 520);
					connector.add(server.appendFilter(() -> new SSLFilter(connector,
							connector, server.ssltq, sslcServer,
							SSLFilter.SERVER_NO_AUTH)), addr);
				} else if (cipher) {
					connector.add(server.appendFilter(() -> new CipherFilter(cipherKey)), addr);
				} else {
					connector.add(server, addr);
				}
			}
			Log.i("Tuna Proxy Started on " + binds);
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