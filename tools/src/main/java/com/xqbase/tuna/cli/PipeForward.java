package com.xqbase.tuna.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;

public class PipeForward {
	public static void main(String[] args) {
		if (args.length < 3) {
			System.out.println("Pipe Forward Usage: java " + PipeForward.class.getName() +
					" <named-pipe> <remote-host> <remote-port>");
			return;
		}

		String pipe = args[0];
		String remoteHost = args[1];
		int remotePort = Integer.parseInt(args[2]);
		Thread thread = null;
		try (
			RandomAccessFile file = new RandomAccessFile(pipe, "rws");
			Socket socket = new Socket(remoteHost, remotePort);
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
		) {
			Thread main = Thread.currentThread();
			thread = new Thread(() -> {
				byte[] b = new byte[1024];
				int len;
				try {
					while (!Thread.interrupted() && (len = in.read(b)) >= 0) {
						if (len == 0) {
							Thread.sleep(1);
						} else {
							file.write(b, 0, len);
						}
					}
				} catch (InterruptedException e) {
					// Interrupted
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					main.interrupt();
				}
			});
			thread.start();
			byte[] b = new byte[1024];
			int len;
			while (!Thread.interrupted() && (len = file.read(b)) >= 0) {
				if (len == 0) {
					Thread.sleep(1);
				} else {
					out.write(b, 0, len);
				}
			}
		} catch (InterruptedException e) {
			// Interrupted
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (thread != null) {
				thread.interrupt();
			}
		}
	}
}