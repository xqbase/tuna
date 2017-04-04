package com.xqbase.tuna.mux;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

class IdPool {
	private static final int MAX_ID = 65535;

	private int nextId = 0;
	private Deque<Integer> returned = new ArrayDeque<>();
	private Set<Integer> borrowed = new HashSet<>();

	int borrowId() {
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

	void returnId(int id) {
		Integer i = Integer.valueOf(id);
		if (borrowed.remove(i)) {
			returned.offer(i);
		}
	}
}