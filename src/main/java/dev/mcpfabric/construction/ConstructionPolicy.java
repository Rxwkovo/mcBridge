package dev.mcpfabric.construction;

import java.util.Set;

/** Small, loader-independent policy helpers used by the region editor. */
public final class ConstructionPolicy {
	private ConstructionPolicy() {}
	public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public Bounds {
			if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid bounds");
		}
		public boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
	}
	public static boolean allowedBlock(String id) {
		return "minecraft:air".equals(id) || "minecraft:stone".equals(id) || "create:shaft".equals(id);
	}
	public static boolean allowedBlock(String id, Set<String> extra) {
		return allowedBlock(id) || (id != null && extra != null && extra.contains(id));
	}
	public static boolean validKey(String key) {
		return key != null && key.matches("[A-Za-z0-9._:-]{1,96}");
	}
	public static String completedStatus(boolean undo, java.util.List<String> results) {
		boolean failure = results.stream().anyMatch(value -> value.equals("state_conflict") || value.equals("unloaded_or_outside") || value.equals("not_changed"));
		return failure ? "partial" : (undo ? "undone" : "completed");
	}
	/** Classifies an observation, not the causal history of a block. */
	public static String classifyRecovery(boolean loaded, boolean matchesBefore, boolean matchesAfter) {
		if (!loaded) return "unavailable_unloaded";
		if (matchesBefore && matchesAfter) return "uncertain_identical_states";
		if (matchesBefore) return "matches_before";
		if (matchesAfter) return "matches_after";
		return "conflict";
	}
}
