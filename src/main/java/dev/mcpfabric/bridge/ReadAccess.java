package dev.mcpfabric.bridge;

import java.util.Set;

/**
 * What the read-only token ({@code readToken}) may call: methods that only look at the game. They
 * still need their own lock (vision, perception, player control for screens), like with the full
 * token. Anything not listed here, including methods added later, is refused for that token.
 */
public final class ReadAccess {
	static final Set<String> METHODS = Set.of(
			"info.status", "info.capabilities", "info.environmentCapabilities", "session.info", "construction.query", "telemetry.list", "telemetry.get", "telemetry.history", "telemetry.metrics", "telemetry.kineticHistory", "telemetry.comparePeriods", "telemetry.queryMeter",
			"world.listMovingStructures", "world.inspectMovingStructure", "world.getStructureBlocks", "world.inspectStructureMotion",
			"world.runtimeCatalog", "world.inspectRecipe", "world.networkSummary", "world.networkJobStart", "world.networkJobStatus", "world.networkJobCancel", "world.networkJobPage", "world.inspectCreateNetwork", "world.inspectCreateKinetic", "world.inspectBlockEntity", "world.listRegisteredBlocks", "world.getBlock", "world.getBlocks", "world.findBlocks", "world.getTimeAndWeather", "world.getDimensions", "world.raycast",
			"entities.query", "entities.get", "players.list", "players.get",
			"chat.getRecent", "events.getRecent",
			"player.getState", "player.getInventory", "player.getEquipment", "player.getStatusEffects",
			"vision.screenshot", "vision.describeScene",
			"perception.scan", "perception.blocks", "perception.entities",
			"recipes.query", "nav.status", "screen.get", "ui.widgets", "session.worlds", "supervised.status");

	private ReadAccess() {}

	public static boolean allowed(String method) {
		return method != null && METHODS.contains(method);
	}
}
