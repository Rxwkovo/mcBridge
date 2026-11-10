package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Reads an existing network cache, with bounded immutable member pages. */
public interface KineticNetworkObserver {
	JsonObject summary(BlockEntity anchor);
	JsonObject inspect(BlockEntity anchor, int offset, int limit);
	JsonObject page(String snapshotId, int offset, int limit);
}
