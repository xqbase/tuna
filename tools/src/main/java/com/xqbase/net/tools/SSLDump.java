package com.xqbase.net.tools;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateValidity;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

import com.xqbase.net.ConnectorImpl;
import com.xqbase.net.misc.DumpFilter;
import com.xqbase.net.misc.ForwardServer;
import com.xqbase.net.ssl.SSLFilter;
import com.xqbase.util.Conf;
import com.xqbase.util.Log;
import com.xqbase.util.Numbers;
import com.xqbase.util.Service;
import com.xqbase.util.Time;

public class SSLDump {
	private static X509CertImpl sign(String dn, KeyPair keyPair)
			throws IOException, GeneralSecurityException {
		long now = System.currentTimeMillis();
		X509CertInfo info = new X509CertInfo();
		info.set("version", new CertificateVersion(2));
		info.set("serialNumber", new CertificateSerialNumber(0));
		info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get("SHA1withRSA")));
		X500Name x500Name = new X500Name(dn);
		info.set("subject", x500Name);
		info.set("key", new CertificateX509Key(keyPair.getPublic()));
		info.set("validity", new CertificateValidity(new
				Date(now), new Date(now + Time.WEEK * 52)));
		info.set("issuer", x500Name);
		X509CertImpl cert = new X509CertImpl(info);
		cert.sign(keyPair.getPrivate(), "SHA1withRSA");
		return cert;
	}

	private static SSLContext getSSLContext(KeyManagerFactory kmf)
			throws GeneralSecurityException {
		SSLContext sslc = SSLContext.getInstance("TLS");
		sslc.init(kmf == null ? new KeyManager[0] : kmf.getKeyManagers(),
				new X509TrustManager[] {
			new X509TrustManager() {
				@Override
				public void checkClientTrusted(X509Certificate[] certs, String s) {/**/}

				@Override
				public void checkServerTrusted(X509Certificate[] certs, String s) {/**/}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			}
		}, null);
		return sslc;
	}

	private static Service service = new Service();

	public static void main(String[] args) {
		if (!service.startup(args)) {
			return;
		}
		if (args.length < 2) {
			System.out.println("SSLDump Usage: java -cp net.jar " +
					"com.xqbase.net.tools.SSLDump <host-name> <host-addr> [<port>]");
			service.shutdown();
			return;
		}

		Logger logger = Log.getAndSet(Conf.openLogger("SSLDump.", 16777216, 10));
		String hostName = args[0];
		String hostAddr = args[1];
		int port = args.length < 3 ? 443 : Numbers.parseInt(args[2], 443);
		Log.i(String.format("SSLDump Started (%s:%s->%s:%s)",
				hostName, "" + port, hostAddr, "" + port));
		try (ConnectorImpl connector = new ConnectorImpl()) {
			service.addShutdownHook(connector::interrupt);
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, null);
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(1024);
			KeyPair keyPair = kpg.genKeyPair();
			ks.setKeyEntry("", keyPair.getPrivate(), new char[0],
					new X509Certificate[] {sign("CN=" + hostName, keyPair)});
			KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
			kmf.init(ks, new char[0]);
			SSLContext sslc = getSSLContext(kmf);
			SSLContext sslcClient = getSSLContext(null);

			ForwardServer server = new ForwardServer(connector, hostAddr, port);
			server.appendRemoteFilter(() -> new SSLFilter(connector, sslcClient, SSLFilter.CLIENT));
			connector.add(server.appendFilter(() -> new DumpFilter().setDumpText(true)).
					appendFilter(() -> new SSLFilter(connector, sslc, SSLFilter.SERVER_NO_AUTH)), 443);
			connector.doEvents();
		} catch (IOException | GeneralSecurityException e) {
			Log.w(e.getMessage());
		} catch (Error | RuntimeException e) {
			Log.e(e);
		}

		Log.i("SSLDump Stopped");
		Conf.closeLogger(Log.getAndSet(logger));
		service.shutdown();
	}
}