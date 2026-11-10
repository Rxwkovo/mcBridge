package dev.mcpfabric.adapters;

import org.junit.jupiter.api.Test;

import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FailFastCursorTest {
	@Test
	void progressesThroughLargeStableMapAcrossBoundedSlices() {
		Map<Integer, Integer> map = new HashMap<>();
		for (int i = 0; i < 16384; i++) map.put(i, i);
		FailFastCursor cursor = new FailFastCursor(3);
		Set<Object> seen = new HashSet<>();
		int slices = 0;
		while (true) {
			assertTrue(cursor.attach(map));
			for (int i = 0; i < 128 && cursor.hasNext(); i++) seen.add(cursor.next().getKey());
			slices++;
			if (!cursor.hasNext()) break;
		}
		assertEquals(16384, seen.size());
		assertEquals(128, slices);
		assertFalse(cursor.changed());
		cursor.clear();
	}

	@Test
	void detectsMutationRestartsAndRefusesUnboundedRetry() {
		Map<Integer, Integer> map = new HashMap<>();
		map.put(1, 1); map.put(2, 2);
		FailFastCursor cursor = new FailFastCursor(1);
		assertTrue(cursor.attach(map));
		cursor.next();
		map.put(3, 3);
		assertThrows(ConcurrentModificationException.class, cursor::next);
		assertTrue(cursor.restart());
		assertTrue(cursor.changed());
		assertTrue(cursor.attach(map));
		assertFalse(cursor.restart());
		assertEquals(2, cursor.restarts());
	}

	@Test
	void replacedMapConsumesRestartBudget() {
		FailFastCursor cursor = new FailFastCursor(1);
		assertTrue(cursor.attach(Map.of(1, 1)));
		assertTrue(cursor.attach(Map.of(2, 2)));
		assertTrue(cursor.changed());
		assertFalse(cursor.attach(Map.of(3, 3)));
	}
}
