package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import dev.mcpfabric.McpFabric;

/** Read-only adapter for static-world Create 6.0.10 kinetic block entities.
 * The caller must resolve the loaded block entity on the server thread.
 * No Create class appears in a signature, so the bridge loads without Create.
 */
final class CreateKineticAdapter implements KineticObserver {
	private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private static final String ROTATE = "com.simibubi.create.content.kinetics.base.IRotate";

	@Override
	public JsonObject inspect(BlockEntity entity) {
		try {
			var platform = McpFabric.platform();
			String version = CreateAdapterProvider.version(platform);
			if (version == null) return status("create_not_loaded", "Create is not loaded in NeoForge.");
			if (!"detected".equals(CreateAdapterProvider.status(platform))) {
				JsonObject result = status("unsupported_version", "This adapter is verified against Create 6.0.10 only.");
				result.addProperty("createVersion", version);
				return result;
			}
			if (entity == null) return status("no_block_entity", "No block entity exists at this position.");
			Class<?> kinetic = Class.forName(KINETIC);
			if (!kinetic.isInstance(entity)) return status("not_kinetic", "The block entity is not a static-world kinetic block entity.");

			float rpm = ((Number) kinetic.getMethod("getSpeed").invoke(entity)).floatValue();
			float theoretical = ((Number) kinetic.getMethod("getTheoreticalSpeed").invoke(entity)).floatValue();
			boolean overstressed = (Boolean) kinetic.getMethod("isOverStressed").invoke(entity);
			boolean hasNetwork = (Boolean) kinetic.getMethod("hasNetwork").invoke(entity);
			if (!Float.isFinite(rpm) || !Float.isFinite(theoretical)) return status("invalid_runtime_data", "Create returned a non-finite speed.");
			JsonObject result = status("ok", "Static-world kinetic block entity observed on the server thread.");
			result.addProperty("createVersion", version);
			result.addProperty("rpm", rpm);
			result.addProperty("theoreticalRpm", theoretical);
			result.addProperty("rotationSign", rpm > 0 ? "positive" : rpm < 0 ? "negative" : "stopped");
			result.addProperty("rotationSignSource", "Create KineticBlockEntity.getSpeed; sign follows Create axis convention");
			result.addProperty("overstressed", overstressed);
			result.addProperty("hasNetwork", hasNetwork);
			result.addProperty("source", "Create KineticBlockEntity runtime fields and public methods");
			try {
			Object id = kinetic.getField("network").get(entity);
			if (id != null) result.addProperty("networkId", id.toString());
			Object source = kinetic.getField("source").get(entity);
			if (source instanceof BlockPos p) {
				JsonObject pos = new JsonObject();
				pos.addProperty("x", p.getX()); pos.addProperty("y", p.getY()); pos.addProperty("z", p.getZ());
				result.add("sourcePosition", pos);
			}
			} catch (ReflectiveOperationException | SecurityException ex) {
				result.addProperty("networkIdentityStatus", "unavailable");
			}
			readAxis(entity, result);
			if (hasNetwork) readNetworkSnapshot(kinetic, entity, result);
			else result.addProperty("networkStatus", "not_established");
			result.addProperty("contraptionSupport", "unsupported: static-world block entities only");
			return result;
		} catch (ReflectiveOperationException | LinkageError | SecurityException e) {
			return status("adapter_unavailable", "Create API access failed: " + e.getClass().getSimpleName());
		}
	}

	private static void readAxis(BlockEntity entity, JsonObject result) {
		try {
			Class<?> rotate = Class.forName(ROTATE);
			BlockState state = entity.getBlockState();
			Object block = state.getBlock();
			if (!rotate.isInstance(block)) { result.addProperty("rotationAxisStatus", "unavailable"); return; }
			Object axis = rotate.getMethod("getRotationAxis", BlockState.class).invoke(block, state);
			result.addProperty("rotationAxis", String.valueOf(axis));
		} catch (ReflectiveOperationException | LinkageError | SecurityException e) {
			result.addProperty("rotationAxisStatus", "unavailable");
		}
	}

	/** Cached values last supplied by Create's updateFromNetwork; do not create or recalculate a network. */
	static void readNetworkSnapshot(Class<?> kinetic, Object entity, JsonObject result) {
		try {
			Field capacity = kinetic.getDeclaredField("capacity"), stress = kinetic.getDeclaredField("stress");
			Field size = kinetic.getDeclaredField("networkSize");
			if (!capacity.trySetAccessible() || !stress.trySetAccessible() || !size.trySetAccessible()) {
				result.addProperty("networkStatus", "unavailable"); return;
			}
			float cap = capacity.getFloat(entity), used = stress.getFloat(entity);
			int count = size.getInt(entity);
			boolean dirty = kinetic.getField("networkDirty").getBoolean(entity);
			if (!Float.isFinite(cap) || !Float.isFinite(used)) { result.addProperty("networkStatus", "invalid_data"); return; }
			result.addProperty("networkStatus", "cached_snapshot");
			result.addProperty("networkStressCapacity", cap);
			result.addProperty("networkStressConsumption", used);
			result.addProperty("networkSize", count);
			result.addProperty("networkSnapshotSource", "Create KineticBlockEntity.updateFromNetwork cached values");
			result.addProperty("networkDirty", dirty);
		} catch (ReflectiveOperationException | SecurityException e) {
			result.addProperty("networkStatus", "unavailable");
		}
	}

	private static JsonObject status(String code, String message) {
		JsonObject result = new JsonObject();
		result.addProperty("status", code);
		result.addProperty("message", message);
		return result;
	}
}
