package com.xqbase.tuna.allinone;

import java.util.ArrayDeque;
import java.util.HashSet;

public class IdPool {
	private static final int MAX_ID = 65535;

	private int nextId = 0;

	private ArrayDeque<Integer> returned = new ArrayDeque<>();
	private HashSet<Integer> borrowed = new HashSet<>();

	public int borrowId() {
		Integer i = returned.poll();
		if (i == null) {
			if (nextId > MAX_ID) {
				return -1;
			}
			i = Integer.valueOf(nextId);
			nextId ++;
		}
		borrowed.add(i);
		return i.intValue();
	}

	public void returnId(int id) {
		Integer i = Integer.valueOf(id);
		if (borrowed.remove(i)) {
			returned.offer(i);
		}
	}
}