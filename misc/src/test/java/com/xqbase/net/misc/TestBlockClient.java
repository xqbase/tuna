package com.xqbase.net.misc;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import com.xqbase.util.Streams;

public class TestBlockClient {
	public static void main(String[] args) {
		byte[] bb = new byte[1024];
		try (
			Socket socket = new Socket("localhost", 23);
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
		) {
			System.out.println(socket.getLocalPort());
			new Thread(() -> {
				try {
					Streams.copy(in, new OutputStream() {
						private int count = 0;

						@Override
						public void write(int b) throws IOException {
							count ++;
							if (count == 65536) {
								count = 0;
								System.out.print('-');
							}
						}
					});
				} catch (IOException e) {
					System.err.println(e.getMessage());
				}
			}).start();
			int count = 0;
			while (true) {
				out.write(bb);
				count += bb.length;
				if (count == 65536) {
					count = 0;
					System.out.print('+');
				}
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
		}
	}
}