package com.xqbase.tuna.proxy.util;

public class LinkedEntry<T> {
	private LinkedEntry<T> next = this, prev = this;
	private T object;

	public LinkedEntry(T object) {
		this.object = object;
	}

	public T getObject() {
		return object;
	}

	public LinkedEntry<T> getNext() {
		return next;
	}

	public LinkedEntry<T> getPrev() {
		return prev;
	}

	public void remove() {
		prev.next = next;
		next.prev = prev;
		next = prev = null;
	}

	public void clear() {
		next = prev = this;
	}

	public void addPrev(LinkedEntry<T> e) {
		e.next = this;
		e.prev = prev;
		prev.next = e;
		prev = e;
	}

	public void addNext(LinkedEntry<T> e) {
		e.prev = this;
		e.next = next;
		next.prev = e;
		next = e;
	}
}