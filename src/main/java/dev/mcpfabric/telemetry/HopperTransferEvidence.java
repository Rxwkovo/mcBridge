package dev.mcpfabric.telemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Pure validation for a prospective one-item hopper boundary; this does not observe the game. */
public final class HopperTransferEvidence {
	private HopperTransferEvidence() {}
	public record Result(boolean consistent, String status, String itemId, Long count) {}
	/**
	 * The caller must capture a single actual extraction and the same hopper's before/after slots
	 * in one server-thread call. Values are counts by item registry ID; components are not exposed.
	 * This checks supplied counts for consistency; it does not establish their provenance,
	 * verify item components, deduplicate callbacks or persist events.
	 */
	public static Result validate(boolean expectedSource, boolean simulated, boolean hookSucceeded, boolean matchingComponents,
			String extractedId, long extractedCount, Map<String, Long> before, Map<String, Long> after) {
		if (!expectedSource) return unavailable("source_unverified");
		if (simulated) return unavailable("simulated");
		if (!hookSucceeded) return unavailable("hook_not_successful");
		if (!matchingComponents) return unavailable("components_unverified_or_mismatched");
		if (!validId(extractedId) || extractedCount != 1) return unavailable("invalid_actual_extraction");
		if (!valid(before) || !valid(after)) return unavailable("invalid_destination_snapshot");
		Set<String> ids = new HashSet<>(before.keySet()); ids.addAll(after.keySet());
		try {
			Map<String, Long> changes = new HashMap<>();
			for (String id : ids) {
				long delta = Math.subtractExact(after.getOrDefault(id, 0L), before.getOrDefault(id, 0L));
				if (delta != 0) changes.put(id, delta);
			}
			if (changes.size() != 1 || changes.getOrDefault(extractedId, 0L) != extractedCount) return unavailable("destination_mismatch");
			return new Result(true, "evidence_consistent", extractedId, extractedCount);
		} catch (ArithmeticException ex) { return unavailable("count_overflow"); }
	}
	private static boolean valid(Map<String, Long> counts) {
		if (counts == null || counts.size() > 5) return false;
		for (var e : counts.entrySet()) if (!validId(e.getKey()) || e.getValue() == null || e.getValue() < 0 || e.getValue() > 320) return false;
		return true;
	}
	private static boolean validId(String id) { return id != null && id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"); }
	private static Result unavailable(String reason) { return new Result(false, reason, null, null); }
}
