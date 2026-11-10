package dev.mcpfabric.adapters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** In-memory immutable serialized snapshots; synchronization covers expiry and capacity limits. */
final class BoundedSnapshotCache {
	static final class Snapshot {
		final String metadata;
		final List<String> records;
		final long createdAt;
		Snapshot(String metadata, List<String> records, long createdAt) {
			this.metadata = metadata;
			this.records = List.copyOf(records);
			this.createdAt = createdAt;
		}
	}
	private final int capacity;
	private final int maxRecords;
	private final long ttlNanos;
	private final LongSupplier clock;
	private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();

	BoundedSnapshotCache(int capacity, int maxRecords, long ttlNanos, LongSupplier clock) {
		if (capacity < 1 || maxRecords < 1 || ttlNanos < 1) throw new IllegalArgumentException("Invalid limits");
		this.capacity = capacity; this.maxRecords = maxRecords; this.ttlNanos = ttlNanos; this.clock = clock;
	}

	synchronized String put(String metadata, List<String> records) {
		if (metadata == null || metadata.length() > 16384 || records == null || records.size() > maxRecords
				|| records.stream().anyMatch(record -> record == null || record.length() > 2048))
			throw new IllegalArgumentException("Invalid snapshot");
		long now = clock.getAsLong();
		expire(now);
		while (snapshots.size() >= capacity) snapshots.remove(snapshots.keySet().iterator().next());
		String id;
		do { id = UUID.randomUUID().toString(); } while (snapshots.containsKey(id));
		snapshots.put(id, new Snapshot(metadata, new ArrayList<>(records), now));
		return id;
	}

	synchronized Snapshot get(String id) {
		long now = clock.getAsLong();
		expire(now);
		return id == null ? null : snapshots.get(id);
	}

	private void expire(long now) {
		snapshots.entrySet().removeIf(e -> now - e.getValue().createdAt >= ttlNanos);
	}
}
