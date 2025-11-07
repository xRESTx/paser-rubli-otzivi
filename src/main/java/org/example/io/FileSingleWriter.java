package org.example.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

public class FileSingleWriter implements Runnable {
	public static final class Entry {
		public final Path path;
		public final String line;
		public Entry(Path path, String line) { this.path = path; this.line = line; }
	}

	private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
	private final Map<Path, BufferedWriter> writers = new ConcurrentHashMap<>();
	private volatile boolean running = true;

	public void submit(Path path, String line) {
		queue.offer(new Entry(path, line));
	}

	public void stop() {
		running = false;
		queue.offer(new Entry(null, null));
	}

	@Override
	public void run() {
		while (running || !queue.isEmpty()) {
			try {
				Entry e = queue.take();
				if (e.path == null) break;
				BufferedWriter w = writers.computeIfAbsent(e.path, p -> {
					try { return Files.newBufferedWriter(p, CREATE, APPEND); } catch (IOException ex) { return null; }
				});
				if (w != null) {
					w.write(e.line);
					w.newLine();
					w.flush();
				}
			} catch (InterruptedException | IOException ignored) {
			}
        }
		// close writers
		writers.values().forEach(w -> { try { w.close(); } catch (IOException ignored) {} });
		writers.clear();
	}
}


