package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.Takeover;
import net.minecraft.server.MinecraftServer;

/** Status / capability discovery handlers. */
public final class InfoHandlers {
	private InfoHandlers() {}

	public static void register(RpcRouter router) {
		router.register("info.status", ctx -> {
			boolean client = McpFabric.platform().isClient();
			MinecraftServer server = ServerHolder.get();
			boolean serverPresent = server != null;

			JsonObject o = new JsonObject();
			o.addProperty("mod", "mcpfabric");
			o.addProperty("modVersion", McpFabric.platform().modVersion());
			o.addProperty("minecraftVersion", McpFabric.platform().minecraftVersion());
			o.addProperty("loader", McpFabric.platform().loader());
			o.addProperty("accessMode", McpFabric.config().accessMode);
			o.addProperty("adminEnabled", admin());
			o.addProperty("side", client ? "client" : "dedicated_server");
			// The player paused the AI (client only): actions are refused until they resume it.
			o.addProperty("aiPaused", Takeover.paused());
			// Groups whose calls wait for the player's approval; a refused one is not to be retried.
			JsonArray approval = new JsonArray();
			McpFabric.config().requireApproval.forEach(approval::add);
			o.add("requireApproval", approval);
			o.addProperty("serverPresent", serverPresent);
			o.addProperty("integratedServer", client && serverPresent);
			// Benign off-thread read (a single int) — intentionally NOT scheduled onto the game thread
			// so get_status stays responsive even when the server thread is busy/stalled.
			o.addProperty("playerCount", serverPresent ? server.getPlayerCount() : 0);
			o.add("capabilities", capabilities(client, serverPresent));
			o.add("methods", router.methodNames());
			return o;
		});

		router.register("info.environmentCapabilities", ctx -> {
			var platform = McpFabric.platform();
			var config = McpFabric.config();
			boolean serverPresent = ServerHolder.isPresent();
			JsonObject out = new JsonObject();
			out.addProperty("schemaVersion", 1);
			out.addProperty("bridgeVersion", platform.modVersion());
			out.addProperty("minecraftVersion", platform.minecraftVersion());
			out.addProperty("loader", platform.loader());
			out.addProperty("loaderVersion", platform.loadedMods().get(platform.loader()));
			out.addProperty("serverPresent", serverPresent);
			JsonObject mods = new JsonObject();
			platform.loadedMods().forEach(mods::addProperty);
			out.add("loadedMods", mods);
			out.addProperty("modInventoryStatus", mods.size() == 0 ? "unavailable_on_loader" : "detected");
			JsonObject create = new JsonObject();
			create.addProperty("id", "create-native");
			create.addProperty("version", dev.mcpfabric.adapters.CreateAdapterProvider.version(platform));
			create.addProperty("status", dev.mcpfabric.adapters.CreateAdapterProvider.status(platform));
			create.addProperty("supportedVersion", dev.mcpfabric.adapters.CreateAdapterProvider.SUPPORTED_VERSION);
			create.addProperty("verification", "runtime_observation_required");
			create.addProperty("networkUnit", "SU");
			create.addProperty("meterBoundary", platform.supportsFactoryMeter() ? "create_depot_to_vanilla_hopper" : "unsupported");
			out.add("create", create);
			out.add("registeredRpcMethods", router.methodNames());
			JsonObject access = new JsonObject();
			access.addProperty("accessMode", config.accessMode);
			access.addProperty("registryReadEnabled", config.enableRegistryRead);
			access.addProperty("visionEnabled", config.enableVision);
			access.addProperty("worldWriteEnabled", config.enableWorldWrite && admin());
			access.addProperty("regionBuildMode", serverPresent && ("REGION_BUILD".equals(config.accessMode) || admin()));
			access.addProperty("authorization", "evaluated_per_operation");
			out.add("serverPolicy", access);
			JsonObject budgets = new JsonObject();
			budgets.addProperty("networkMemberLimit", config.networkMemberLimit);
			budgets.addProperty("networkTickBudgetMicros", config.networkTickBudgetMicros);
			budgets.addProperty("networkNodesPerTick", config.networkNodesPerTick);
			budgets.addProperty("networkGlobalBudgetBytes", config.networkGlobalBudgetBytes);
			budgets.addProperty("movingRadiusBlocks", 32);
			budgets.addProperty("movingResultLimit", 16);
			budgets.addProperty("movingSnapshotMembers", 512);
			budgets.addProperty("movingPageLimit", 64);
			out.add("budgets", budgets);
			return out;
		});

		router.register("info.capabilities", ctx -> {
			boolean client = McpFabric.platform().isClient();
			boolean serverPresent = ServerHolder.isPresent();

			JsonObject groups = new JsonObject();
			groups.addProperty("info", true);
			groups.addProperty("world_read", serverPresent && McpFabric.config().enableVision);
			groups.addProperty("registry_read", serverPresent && McpFabric.config().enableRegistryRead);
			groups.addProperty("world_write", serverPresent && McpFabric.config().enableWorldWrite);
			groups.addProperty("entities", serverPresent && McpFabric.config().enableVision);
			groups.addProperty("players_admin", serverPresent && McpFabric.config().enableCommands);
			groups.addProperty("command", serverPresent && McpFabric.config().enableCommands);
			groups.addProperty("player_local", client);
			groups.addProperty("control", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("interact", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("inventory", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("chat", true);
			groups.addProperty("vision", client && McpFabric.config().enableVision);
			groups.addProperty("navigation", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("perception", client && McpFabric.config().enableVision);
			groups.addProperty("containers", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("recipes", client);
			groups.addProperty("crafting", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("session", client && McpFabric.config().enablePlayerControl);
			groups.addProperty("ui", client && McpFabric.config().enablePlayerControl && McpFabric.config().enableUiControl);
			groups.addProperty("supervised", McpFabric.config().enableSupervisedControl);
			groups.addProperty("events", true);
			if (!admin()) for (String group : java.util.List.of("world_write", "command", "players_admin", "control", "interact", "inventory", "navigation", "containers", "crafting", "session", "ui", "supervised")) groups.addProperty(group, false);
			groups.addProperty("region_build", serverPresent && "REGION_BUILD".equals(McpFabric.config().accessMode));

			JsonObject o = new JsonObject();
			o.add("groups", groups);
			return o;
		});
	}

	private static JsonArray capabilities(boolean client, boolean serverPresent) {
		JsonArray a = new JsonArray();
		a.add("info");
		a.add("chat");
		a.add("events");
		if (McpFabric.config().enableSupervisedControl) a.add("supervised");
		if (serverPresent) {
			if (McpFabric.config().enableRegistryRead) a.add("registry_read");
			if (McpFabric.config().enableVision) a.add("world_read");
			if (McpFabric.config().enableVision) a.add("entities");
			if (McpFabric.config().enableWorldWrite) a.add("world_write");
			if (McpFabric.config().enableCommands) {
				a.add("command");
				a.add("players_admin");
			}
		}
		if (client) {
			a.add("player_local");
			a.add("recipes");
			if (McpFabric.config().enablePlayerControl) {
				a.add("inventory");
				a.add("control");
				a.add("interact");
				a.add("navigation");
				a.add("containers");
				a.add("crafting");
				a.add("session");
				if (McpFabric.config().enableUiControl) a.add("ui");
			}
			if (McpFabric.config().enableVision) {
				a.add("vision");
				a.add("perception");
			}
		}
		if (!admin()) {
			JsonArray filtered = new JsonArray();
			for (var entry : a) if (!java.util.Set.of("world_write", "command", "players_admin", "control", "interact", "inventory", "navigation", "containers", "crafting", "session", "ui", "supervised").contains(entry.getAsString())) filtered.add(entry);
			a = filtered;
		}
		if (serverPresent && "REGION_BUILD".equals(McpFabric.config().accessMode)) a.add("region_build");
		return a;
	}

	private static boolean admin() {
		return "ADMIN".equals(McpFabric.config().accessMode) && McpFabric.config().allowAdmin;
	}
}
