package com.xqbase.net.misc;

import com.xqbase.net.FilterFactory;

/** A Factory to create {@link ZLibFilter}s. */
public class ZLibFilterFactory implements FilterFactory {
	@Override
	public ZLibFilter createFilter() {
		return new ZLibFilter();
	}
}