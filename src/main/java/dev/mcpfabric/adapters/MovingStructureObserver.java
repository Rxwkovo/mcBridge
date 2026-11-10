package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Loaded server entities only. Implementations never assemble, move or load a structure. */
public interface MovingStructureObserver {
	JsonObject list(ServerLevel level, Vec3 center, int radius);
	JsonObject sampleArea(ServerLevel level, AABB bounds);
	JsonObject inspect(ServerLevel level, UUID id);
	JsonObject blocks(ServerLevel level, UUID id, String snapshotId, int offset, int limit) throws RpcException;
	void clear();
}
