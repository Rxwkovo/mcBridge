package dev.mcpfabric.adapters;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;

/** Single-thread cursor: retaining a fail-fast iterator avoids O(n²) repeated prefix scans. */
final class FailFastCursor {
	private final int maxRestarts;
	private Object identity;
	private Iterator<? extends Map.Entry<?, ?>> iterator;
	private int restarts;
	private boolean changed;
	FailFastCursor(int maxRestarts) { this.maxRestarts = maxRestarts; }
	boolean attach(Map<?, ?> map) {
		if (identity != null && identity != map && !restart()) return false;
		if (iterator == null) { identity = map; iterator = map.entrySet().iterator(); }
		return true;
	}
	boolean hasNext() { return iterator.hasNext(); }
	Map.Entry<?, ?> next() throws ConcurrentModificationException { return iterator.next(); }
	boolean restart() {
		changed = true; iterator = null; identity = null;
		return ++restarts <= maxRestarts;
	}
	int restarts() { return restarts; }
	boolean changed() { return changed; }
	void clear() { iterator = null; identity = null; }
}
