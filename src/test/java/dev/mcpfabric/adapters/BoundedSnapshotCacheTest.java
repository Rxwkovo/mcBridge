package dev.mcpfabric.adapters;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BoundedSnapshotCacheTest {
	@Test
	void immutableSnapshotsExpireAndOldestIsEvicted() {
		AtomicLong time = new AtomicLong(1);
		BoundedSnapshotCache cache = new BoundedSnapshotCache(2, 3, 100, time::get);
		List<String> input = new ArrayList<>(List.of("first", "second"));
		String first = cache.put("meta", input);
		input.set(0, "mutated");
		assertEquals("first", cache.get(first).records.get(0));
		assertThrows(UnsupportedOperationException.class, () -> cache.get(first).records.add("third"));
		String second = cache.put("two", List.of());
		String third = cache.put("three", List.of());
		assertNull(cache.get(first));
		assertNotNull(cache.get(second));
		assertNotNull(cache.get(third));
		time.set(101);
		assertNull(cache.get(second));
		assertNull(cache.get(third));
	}

	@Test
	void enforcesCountAndSerializedSize() {
		BoundedSnapshotCache cache = new BoundedSnapshotCache(1, 2, 100, () -> 0);
		assertThrows(IllegalArgumentException.class, () -> cache.put("m", List.of("a", "b", "c")));
		assertThrows(IllegalArgumentException.class, () -> cache.put("m", List.of("a".repeat(2049))));
		assertThrows(IllegalArgumentException.class, () -> cache.put("x".repeat(16385), List.of()));
	}
}
