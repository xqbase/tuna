package com.xqbase.tuna.misc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Calendar;

import com.xqbase.tuna.ConnectionFilter;
import com.xqbase.tuna.ConnectionSession;
import com.xqbase.tuna.util.Bytes;

/** A {@link ConnectionFilter} which dumps sent and received data into console or files. */
public class DumpFilter extends ConnectionFilter {
	private static String now() {
		Calendar cal = Calendar.getInstance();
		int hour = cal.get(Calendar.HOUR_OF_DAY);
		int minute = cal.get(Calendar.MINUTE);
		int second = cal.get(Calendar.SECOND);
		int millis = cal.get(Calendar.MILLISECOND);
		return String.format("%02d:%02d:%02d.%03d", Integer.valueOf(hour),
				Integer.valueOf(minute), Integer.valueOf(second), Integer.valueOf(millis));
	}

	private PrintStream dumpStream = System.out;
	private File dumpFolder = null;
	private boolean dumpText = false, useClientMode = false;

	private PrintStream out = null;
	private FileOutputStream outSent, outRecv;
	private String host = "0.0.0.0";
	private int port = 0;

	private FileOutputStream getOutputStream(String suffix) throws IOException {
		File file = dumpFolder;
		file.mkdir();
		// IPv6 Addresses may contain ':' that cannot be used in Windows filename
		file = new File(file.getPath() + File.separator + host.replace(':', '_'));
		file.mkdir();
		file = new File(file.getPath() + File.separator + port + suffix);
		return new FileOutputStream(file, true);
	}

	private void println(String x) {
		if (dumpStream != null) {
			dumpStream.println(x);
		}
		if (dumpFolder != null) {
			out.println(x);
		}
	}

	private void printfln(String format, Object... args) {
		if (dumpStream != null) {
			dumpStream.printf(format, args);
			dumpStream.println();
		}
		if (dumpFolder != null) {
			out.printf(format, args);
			out.println();
		}
	}

	private void dump(byte[] b, int off, int len) {
		if (dumpStream != null) {
			Bytes.dump(dumpStream, b, off, len);
		}
		if (dumpFolder != null) {
			Bytes.dump(out, b, off, len);
		}
	}

	private void dump(byte[] b, int off, int len, boolean sent) {
		printfln("[%s:%s %s at %s]", host, "" + port,
				sent ? "Sent" : "Received", now());
		if (dumpText) {
			println(new String(b, off, len).replace("\7", ""));
		} else {
			dump(b, off, len);
		}
		if (dumpFolder != null) {
			try {
				if (sent) {
					outSent.write(b, off, len);
					outSent.flush();
				} else {
					outRecv.write(b, off, len);
					outRecv.flush();
				}
			} catch (IOException e) {/**/}
		}
	}

	/**
	 * @param dumpStream - The PrintSteam to dump out.
	 * @return The DumpFilter itself.
	 */
	public DumpFilter setDumpStream(PrintStream dumpStream) {
		this.dumpStream = dumpStream;
		return this;
	}

	/**
	 * @param dumpFolder - The folder to dump out.
	 * @return The DumpFilter itself.
	 */
	public DumpFilter setDumpFolder(File dumpFolder) {
		this.dumpFolder = dumpFolder;
		return this;
	}

	/**
	 * @param dumpText - Whether dump in text mode (true) or in binary mode (false).
	 * @return The DumpFilter itself.
	 */
	public DumpFilter setDumpText(boolean dumpText) {
		this.dumpText = dumpText;
		return this;
	}

	/**
	 * @param useClientMode - Whether dump local address (true) or remote address (false).
	 * @return The DumpFilter itself.
	 */
	public DumpFilter setUseClientMode(boolean useClientMode) {
		this.useClientMode = useClientMode;
		return this;
	}

	@Override
	public void send(byte[] b, int off, int len) {
		super.send(b, off, len);
		// Dump After Consume
		dump(b, off, len, useClientMode);
	}

	@Override
	public void onRecv(byte[] b, int off, int len) {
		dump(b, off, len, !useClientMode);
		// Dump Before Consume
		super.onRecv(b, off, len);
	}

	@Override
	public void onConnect(ConnectionSession session) {
		if (useClientMode) {
			host = session.getLocalAddr();
			port = session.getLocalPort();
		} else {
			host = session.getRemoteAddr();
			port = session.getRemotePort();
		}
		if (dumpFolder != null) {
			try {
				out = new PrintStream(getOutputStream(".txt"), true);
				outSent = getOutputStream("_sent.bin");
				outRecv = getOutputStream("_recv.bin");
			} catch (IOException e) {
				dumpFolder = null;
			}
		}
		printfln("[%s:%s Connected at %s]", host, "" + port, now());
		// Dump Before Consume
		super.onConnect(session);
	}

	@Override
	public void onDisconnect() {
		disconnect(!useClientMode);
		// Dump Before Consume
		super.onDisconnect();
	}

	@Override
	public void disconnect() {
		super.disconnect();
		// Dump After Consume
		disconnect(useClientMode);
	}

	private void disconnect(boolean active) {
		printfln("[%s:%s %s at %s]", host, "" + port,
				active ? "Disconnected" : "Kicked", now());
		if (dumpFolder != null) {
			dumpFolder = null;
			try {
				out.close();
				outSent.close();
				outRecv.close();
			} catch (IOException e) {/**/}
		}
	}
}