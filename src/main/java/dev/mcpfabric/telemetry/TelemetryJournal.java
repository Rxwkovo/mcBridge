package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Background-only disk I/O; fail closed instead of overwriting or rotating existing data. */
final class TelemetryJournal {
	static final long MAX_BYTES = 4L * 1024 * 1024;
	private static final long DEFAULT_QUOTA = 64L * 1024 * 1024;
	private final long segmentLimit, diskQuota;
	private volatile long bytesOnDisk;
	private volatile Path dataDirectory;
	private volatile long recoveredLastSequence;
	private volatile java.util.Set<String> truncatedMonitors = java.util.Set.of();
	private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong queuedCount = new AtomicLong(), persistedCount = new AtomicLong(), durableCount = new AtomicLong();
	private volatile boolean ready, running = true;
	private volatile String failure;
	private volatile long quarantinedTailBytes;
	private volatile List<JsonObject> recovered = List.of();
	private final Thread thread;

	TelemetryJournal(Path worldRoot, TelemetryJournal previous) {
		this(worldRoot, previous, MAX_BYTES, configuredQuota());
	}
	private static long configuredQuota() {
		try {
			long configured = Long.getLong("mechagent.telemetry.diskQuotaBytes", DEFAULT_QUOTA);
			return Math.max(MAX_BYTES, Math.min(configured, 512L * 1024 * 1024));
		} catch (SecurityException e) { return DEFAULT_QUOTA; }
	}
	TelemetryJournal(Path worldRoot, TelemetryJournal previous, long segmentLimit, long diskQuota) {
		if (segmentLimit < 128 || diskQuota < segmentLimit) throw new IllegalArgumentException("invalid journal limits");
		this.segmentLimit = segmentLimit; this.diskQuota = diskQuota;
		thread = new Thread(() -> {
			if (previous != null) {
				try { previous.thread.join(); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); failure = "startup_interrupted"; return; }
			}
			run(worldRoot);
		}, "mechagent-telemetry-journal");
		thread.setDaemon(true);
		thread.start();
	}
	boolean ready() { return ready; }
	String failure() { return failure; }
	long dropped() { return dropped.get(); }
	long queuedCount() { return queuedCount.get(); }
	long persistedCount() { return persistedCount.get(); }
	long durableCount() { return durableCount.get(); }
	long quarantinedTailBytes() { return quarantinedTailBytes; }
	long recoveredLastSequence() { return recoveredLastSequence; }
	long diskQuotaBytes() { return diskQuota; }
	long bytesOnDisk() { return bytesOnDisk; }
	Path dataDirectory() { return dataDirectory; }
	boolean recoveredHistoryTruncated(String id) { return truncatedMonitors.contains(id); }
	List<JsonObject> recovered() { return recovered; }
	boolean append(JsonObject record) {
		if (!ready || failure != null || !running) return false;
		String line = record.toString() + "\n";
		if (line.length() > 8192 || !queue.offer(line)) { dropped.incrementAndGet(); return false; }
		queuedCount.incrementAndGet();
		return true;
	}
	void close() {
		running = false;
		try { thread.join(5_000); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		if (thread.isAlive()) failure = "shutdown_flush_timeout";
	}

	private void run(Path worldRoot) {
		try {
			Path root = worldRoot.toRealPath();
			Path data = root.resolve("data");
			Files.createDirectories(data);
			if (!data.toRealPath().startsWith(root)) throw new IllegalStateException("data directory escapes world");
			dataDirectory = data;
			Path legacy = data.resolve("mechagent-telemetry.jsonl");
			List<Path> segments = new ArrayList<>();
			if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) segments.add(legacy);
			try (var stream = Files.list(data)) {
				segments.addAll(stream.filter(p -> p.getFileName().toString().matches("mechagent-telemetry-[0-9]{8}\\.jsonl"))
						.sorted().toList());
			}
			if (segments.size() > 4096) throw new IllegalStateException("too many segments");
			Replay replay = new Replay(); long total = 0;
			for (int i = 0; i < segments.size(); i++) {
				Path segment = segments.get(i);
				if (Files.isSymbolicLink(segment) || !Files.isRegularFile(segment, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("invalid segment");
				long n = Files.size(segment); if (n > Math.max(segmentLimit, MAX_BYTES) || total + n > diskQuota) throw new IllegalStateException("journal exceeds limit");
				byte[] bytes = Files.readAllBytes(segment);
				if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
					if (i != segments.size() - 1) throw new IllegalStateException("incomplete nonfinal segment");
					int start = bytes.length - 1; while (start >= 0 && bytes[start] != '\n') start--; start++;
					byte[] tail = java.util.Arrays.copyOfRange(bytes, start, bytes.length);
					Path quarantine = data.resolve("mechagent-telemetry-tail-" + java.util.UUID.randomUUID() + ".corrupt");
					try (FileChannel bad = FileChannel.open(quarantine, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
						ByteBuffer buffer = ByteBuffer.wrap(tail); while (buffer.hasRemaining()) bad.write(buffer); bad.force(true);
					}
					try (FileChannel existing = FileChannel.open(segment, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { existing.truncate(start); existing.force(true); }
					quarantinedTailBytes += tail.length; n = start; bytes = java.util.Arrays.copyOf(bytes, start);
				}
				total += n;
				for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\n")) if (!line.isEmpty()) {
					if (line.length() > 8192) throw new IllegalStateException("invalid journal");
					replay.accept(JsonParser.parseString(line).getAsJsonObject());
				}
			}
			bytesOnDisk = total;
			recovered = replay.records(); recoveredLastSequence = replay.maxSequence; truncatedMonitors = java.util.Set.copyOf(replay.truncated);
			int segmentNo = segments.isEmpty() ? 0 : segmentNumber(segments.get(segments.size() - 1));
			Path path = segments.isEmpty() ? legacy : segments.get(segments.size() - 1);
			long size = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
			FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
			try {
				ready = true;
				while (running || !queue.isEmpty()) {
					String line;
					try { line = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS); }
					catch (InterruptedException e) { continue; }
					if (line == null) continue;
					List<String> batch = new ArrayList<>(); batch.add(line); queue.drainTo(batch, 63);
					for (int i = 0; i < batch.size(); i++) {
						byte[] bytes = batch.get(i).getBytes(StandardCharsets.UTF_8);
						if (bytes.length > segmentLimit || total + bytes.length > diskQuota) {
							failure = "disk_limit"; dropped.addAndGet(batch.size() - i + queue.size()); queue.clear(); break;
						}
						if (size + bytes.length > segmentLimit) {
							file.force(true); durableCount.set(persistedCount.get()); file.close();
							if (++segmentNo > 99_999_999) throw new IllegalStateException("segment number exhausted");
							path = data.resolve(String.format(java.util.Locale.ROOT, "mechagent-telemetry-%08d.jsonl", segmentNo));
							file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
							size = 0;
						}
						ByteBuffer buffer = ByteBuffer.wrap(bytes);
						while (buffer.hasRemaining()) file.write(buffer);
						size += bytes.length; total += bytes.length; bytesOnDisk = total; persistedCount.incrementAndGet();
					}
					// Include file metadata (notably the new length) in the force request.
					// Filesystem/device guarantees and directory-entry durability can still vary.
					file.force(true); durableCount.set(persistedCount.get());
					if (failure != null) break;
				}
			} finally { file.close(); }
		} catch (Exception e) { failure = "journal_error_" + e.getClass().getSimpleName(); }
		finally { ready = false; }
	}
	private static int segmentNumber(Path path) {
		String name = path.getFileName().toString();
		return name.equals("mechagent-telemetry.jsonl") ? 0 : Integer.parseInt(name.substring(20, 28));
	}

	/** Retain only the latest definition, pause and recent samples per monitor during startup. */
	private static final class Replay {
		private static final class State { JsonObject definition, pause; final ArrayDeque<JsonObject> events = new ArrayDeque<>(); }
		final Map<String, State> monitors = new HashMap<>(); final List<JsonObject> other = new ArrayList<>();
		final java.util.Set<String> truncated = new java.util.HashSet<>(); final Map<String, JsonObject> meters = new HashMap<>(); long maxSequence;
		void accept(JsonObject record) {
			if (record.has("sequence")) maxSequence = Math.max(maxSequence, record.get("sequence").getAsLong());
			if (record.has("type") && "meter_state".equals(record.get("type").getAsString())) {
				String id = record.get("meterId").getAsString();
				if (!meters.containsKey(id) && meters.size() >= 16) throw new IllegalStateException("too many meter identities");
				meters.put(id, record); return;
			}
			if (!record.has("monitorId") || !record.has("type")) {
				if (other.size() >= 16384) throw new IllegalStateException("invalid journal"); other.add(record); return;
			}
			String id = record.get("monitorId").getAsString(), type = record.get("type").getAsString();
			if (!monitors.containsKey(id) && monitors.size() >= 64) throw new IllegalStateException("too many monitor identities");
			State state = monitors.computeIfAbsent(id, ignored -> new State());
			if (type.equals("define")) { if (!state.events.isEmpty()) truncated.add(id); state.definition = record; state.events.clear(); }
			else if (type.equals("pause")) state.pause = record;
			else if (type.equals("sample") || type.equals("gap")) { if (state.events.size() == 64) { state.events.removeFirst(); truncated.add(id); } state.events.addLast(record); }
			else throw new IllegalStateException("unknown event");
		}
		List<JsonObject> records() {
			List<JsonObject> out = new ArrayList<>(other); out.addAll(meters.values());
			for (State s : monitors.values()) if (s.definition != null) {
				out.add(s.definition);
				if (s.pause != null) { JsonObject pause = s.pause.deepCopy(); pause.add("sequence", s.definition.get("sequence")); out.add(pause); }
				out.addAll(s.events);
			}
			out.sort(Comparator.comparingLong(x -> x.has("sequence") ? x.get("sequence").getAsLong() : 0));
			return List.copyOf(out);
		}
	}
}
