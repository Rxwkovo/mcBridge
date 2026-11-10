package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.entity.BlockEntity;

/** One loaded static-world device, sampled on the server thread. */
public interface KineticObserver {
	JsonObject inspect(BlockEntity entity);
}
