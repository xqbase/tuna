package com.xqbase.net.misc;

import java.io.File;
import java.io.PrintStream;
import java.util.function.Supplier;

/** A {@link Supplier} to create {@link DumpFilter}s. */
public class DumpServerFilter implements Supplier<DumpFilter> {
	private PrintStream dumpStream = System.out;
	private File dumpFolder = null;
	private boolean dumpText = false, useClientMode = false;

	/**
	 * @param dumpStream - The PrintSteam to dump out.
	 * @return The DumpServerFilter itself.
	 * @see DumpFilter#setDumpStream(PrintStream)
	 */
	public DumpServerFilter setDumpStream(PrintStream dumpStream) {
		this.dumpStream = dumpStream;
		return this;
	}

	/**
	 * @param dumpFolder - The folder to dump out.
	 * @return The DumpServerFilter itself.
	 * @see DumpFilter#setDumpFolder(File)
	 */
	public DumpServerFilter setDumpFolder(File dumpFolder) {
		this.dumpFolder = dumpFolder;
		return this;
	}

	/**
	 * @param dumpText - Whether dump in text mode (true) or in binary mode (false).
	 * @return The DumpServerFilter itself.
	 * @see DumpFilter#setDumpText(boolean)
	 */
	public DumpServerFilter setDumpText(boolean dumpText) {
		this.dumpText = dumpText;
		return this;
	}

	/**
	 * @param useClientMode - Whether dump local address (true) or remote address (false).
	 * @return The DumpServerFilter itself.
	 * @see DumpFilter#setUseClientMode(boolean)
	 */
	public DumpServerFilter setUseClientMode(boolean useClientMode) {
		this.useClientMode = useClientMode;
		return this;
	}

	@Override
	public DumpFilter get() {
		return new DumpFilter().setDumpStream(dumpStream).setDumpFolder(dumpFolder).
				setDumpText(dumpText).setUseClientMode(useClientMode);
	}
}