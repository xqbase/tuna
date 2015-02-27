package com.xqbase.tuna.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Random;

public class Names {
	private static Random random = new Random();
	private static String[] names;

	static {
		ArrayList<String> names_ = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(Names.
				class.getResourceAsStream("Names.txt")))) {
			String line;
			while ((line = in.readLine()) != null) {
				names_.add(line);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		names = names_.toArray(new String[0]);
	}

	public static String get() {
		return names[random.nextInt(names.length)];
	}

	public static void main(String[] args) {
		for (int i = 0; i < names.length; i ++) {
			System.out.println("  \"" + names[i] + "\",");
		}
	}
}