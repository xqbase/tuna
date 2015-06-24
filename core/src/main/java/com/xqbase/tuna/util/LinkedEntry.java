package com.xqbase.tuna.util;

import java.util.function.Consumer;
import java.util.function.Predicate;

public class LinkedEntry<T> {
	private T object;
	private LinkedEntry<T> next = this, prev = this;

	public LinkedEntry(T object) {
		this.object = object;
	}

	public T getNext() {
		return next.object;
	}

	public T getPrev() {
		return prev.object;
	}

	public boolean isEmpty() {
		return next == this || prev == this;
	}

	public void iterateNext(Predicate<T> condition, Consumer<T> action) {
		LinkedEntry<T> e = next;
		while (e != this && condition.test(e.object)) {
			// "action" may break LinkedEntry
			LinkedEntry<T> e_ = e.next;
			action.accept(e.object);
			e = e_;
		}
	}

	public void iteratePrev(Predicate<T> condition, Consumer<T> action) {
		LinkedEntry<T> e = prev;
		while (e != this && condition.test(e.object)) {
			// "action" may break LinkedEntry
			LinkedEntry<T> e_ = e.prev;
			action.accept(e.object);
			e = e_;
		}
	}

	public void remove() {
		prev.next = next;
		next.prev = prev;
		next = prev = null;
	}

	public void clear() {
		next = prev = this;
	}

	public LinkedEntry<T> addPrev(T t) {
		LinkedEntry<T> e = new LinkedEntry<>(t);
		e.next = this;
		e.prev = prev;
		prev.next = e;
		prev = e;
		return e;
	}

	public LinkedEntry<T> addNext(T t) {
		LinkedEntry<T> e = new LinkedEntry<>(t);
		e.prev = this;
		e.next = next;
		next.prev = e;
		next = e;
		return e;
	}
}