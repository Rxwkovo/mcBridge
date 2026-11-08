package dev.mcpfabric.platform;

import java.nio.file.Path;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The few mod-loader services the shared code needs. Each loader entrypoint (Fabric, NeoForge)
 * supplies its own implementation to {@link dev.mcpfabric.McpFabric#init(Platform)}; nothing outside
 * the loader packages may touch loader APIs directly.
 */
public interface Platform {
	/** Loader name as reported by {@code info.status} ({@code "fabric"} / {@code "neoforge"}). */
	String loader();

	/** Directory holding {@code mcpfabric.config.json}. */
	Path configDir();

	/** True on the physical client (singleplayer / connected client), false on a dedicated server. */
	boolean isClient();

	String minecraftVersion();

	String modVersion();

	/** Loader-specific, non-mutating capability inspection; callers run on the server thread. */
	default JsonObject inspectBlockCapabilities(ServerLevel level, BlockPos pos) {
		JsonObject result = new JsonObject();
		result.addProperty("status", "unsupported_loader");
		return result;
	}
}
