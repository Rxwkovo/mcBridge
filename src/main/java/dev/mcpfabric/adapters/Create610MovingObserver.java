package dev.mcpfabric.adapters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Read-only bindings verified against the Create 6.0.10 NeoForge artifact. */
final class Create610MovingObserver implements MovingStructureObserver {
	static final int MAX_RADIUS = 32, MAX_ENTITIES = 16, MAX_BLOCKS = 512, MAX_PAGE = 64;
	private static final long CAPTURE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(3);
	private static final long SNAPSHOT_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final String CREATE = "com.simibubi.create.content.";
	private static final Map<String, String> TYPES = Map.of(
			CREATE + "contraptions.mounted.MountedContraption", "minecart",
			CREATE + "contraptions.bearing.BearingContraption", "bearing",
			CREATE + "contraptions.piston.PistonContraption", "piston",
			CREATE + "contraptions.elevator.ElevatorContraption", "elevator",
			CREATE + "trains.entity.CarriageContraption", "train_carriage");

	private BoundedSnapshotCache snapshots = newSnapshots();
	private Bindings bindings;
	private boolean failed;

	private record Bindings(Class<? extends Entity> type, Method contraption, Method blocks,
			Method global, Method stalled, Method anchor, Method previousAnchor, Method previousInvalid) {
		static Bindings load() throws ReflectiveOperationException {
			var type = Class.forName(CREATE + "contraptions.AbstractContraptionEntity").asSubclass(Entity.class);
			var contraption = type.getMethod("getContraption");
			return new Bindings(type, contraption, contraption.getReturnType().getMethod("getBlocks"),
					type.getMethod("toGlobalVector", Vec3.class, float.class), type.getMethod("isStalled"),
					type.getMethod("getAnchorVec"), type.getMethod("getPrevAnchorVec"), type.getMethod("isPrevPosInvalid"));
		}
	}

	private static BoundedSnapshotCache newSnapshots() {
		return new BoundedSnapshotCache(4, MAX_BLOCKS, SNAPSHOT_NANOS, System::nanoTime);
	}

	@Override
	public void clear() { snapshots = newSnapshots(); }

	private String readiness() {
		String status = CreateAdapterProvider.status(McpFabric.platform());
		if (!"detected".equals(status)) return status;
		if (bindings == null && !failed) {
			try { bindings = Bindings.load(); }
			catch (ReflectiveOperationException | LinkageError | RuntimeException e) { failed = true; }
		}
		return bindings == null ? "adapter_unavailable" : "ok";
	}

	@Override
	public JsonObject list(ServerLevel level, Vec3 center, int radius) {
		return list(level, new AABB(center, center).inflate(radius), false);
	}

	@Override
	public JsonObject sampleArea(ServerLevel level, AABB bounds) {
		return list(level, bounds, true);
	}

	private JsonObject list(ServerLevel level, AABB bounds, boolean telemetry) {
		JsonObject out = envelope(level, readiness());
		if (bindings == null || !"ok".equals(out.get("status").getAsString())) return out;
		var entities = new ArrayList<Entity>();
		level.getEntities(EntityTypeTest.forClass(bindings.type()), bounds,
				entity -> entity.isAlive() && (!telemetry || bounds.contains(entity.position())),
				entities, MAX_ENTITIES + 1);
		JsonArray records = new JsonArray();
		for (int i = 0; i < Math.min(MAX_ENTITIES, entities.size()); i++)
			records.add(inspectEntity(level, entities.get(i), telemetry));
		out.add("structures", records);
		out.addProperty("truncated", entities.size() > MAX_ENTITIES);
		out.addProperty("scope", telemetry ? "loaded_entity_origins_in_monitor_area" : "loaded_entities_intersecting_search_box");
		return out;
	}

	@Override
	public JsonObject inspect(ServerLevel level, UUID id) {
		String status = readiness();
		if (!"ok".equals(status)) return envelope(level, status);
		Entity entity = lookup(level, id);
		return entity == null ? envelope(level, "not_found_or_unloaded") : inspectEntity(level, entity, false);
	}

	private Entity lookup(ServerLevel level, UUID id) {
		Entity entity = level.getEntity(id);
		return bindings.type().isInstance(entity) && entity.isAlive() ? entity : null;
	}

	private JsonObject inspectEntity(ServerLevel level, Entity entity, boolean telemetry) {
		try {
			Object contraption = bindings.contraption().invoke(entity);
			JsonObject out = envelope(level, contraption == null ? "not_initialized" : "ok");
			out.addProperty("structureId", entity.getStringUUID());
			out.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			out.add("entityPosition", vec(entity.position()));
			Entity vehicle = entity.getVehicle();
			if (!telemetry && vehicle != null) {
				out.addProperty("vehicleId", vehicle.getStringUUID());
				out.addProperty("vehicleType", BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
			}
			if (contraption == null) return out;
			out.addProperty("structureType", TYPES.getOrDefault(contraption.getClass().getName(), "other_create"));
			out.addProperty("assembled", true);
			out.addProperty("stalled", (boolean) bindings.stalled().invoke(entity));
			out.add("motion", motion(entity, level.getGameTime()));
			if (!telemetry) {
				out.add("transform", transform(entity));
				out.addProperty("internalCapabilityStatus", "not_supported");
			}
			return out;
		} catch (ReflectiveOperationException | LinkageError | IllegalArgumentException e) {
			JsonObject out = envelope(level, "adapter_unavailable");
			out.addProperty("structureId", entity.getStringUUID());
			return out;
		}
	}

	private JsonObject transform(Entity entity) throws ReflectiveOperationException {
		Vec3 origin = global(entity, Vec3.ZERO);
		JsonObject transform = new JsonObject();
		transform.add("origin", vec(origin));
		transform.add("xAxis", vec(global(entity, new Vec3(1, 0, 0)).subtract(origin)));
		transform.add("yAxis", vec(global(entity, new Vec3(0, 1, 0)).subtract(origin)));
		transform.add("zAxis", vec(global(entity, new Vec3(0, 0, 1)).subtract(origin)));
		transform.addProperty("source", "Create AbstractContraptionEntity.toGlobalVector, partialTick=1");
		return transform;
	}

	private Vec3 global(Entity entity, Vec3 local) throws ReflectiveOperationException {
		return (Vec3) bindings.global().invoke(entity, local, 1f);
	}

	private JsonObject motion(Entity entity, long tick) throws ReflectiveOperationException {
		Vec3 anchor = (Vec3) bindings.anchor().invoke(entity);
		JsonObject motion = new JsonObject();
		motion.add("anchor", vec(anchor));
		motion.addProperty("toGameTime", tick);
		if (tick > 0 && !(boolean) bindings.previousInvalid().invoke(entity)) {
			Vec3 previous = (Vec3) bindings.previousAnchor().invoke(entity);
			motion.add("previousAnchor", vec(previous));
			motion.add("anchorDisplacementBlocks", vec(anchor.subtract(previous)));
			motion.addProperty("fromGameTime", tick - 1);
			motion.addProperty("status", "native_anchor_delta");
		} else motion.addProperty("status", "previous_anchor_unavailable");
		motion.addProperty("angularVelocityStatus", "not_supported");
		return motion;
	}

	@Override
	public JsonObject blocks(ServerLevel level, UUID id, String snapshotId, int offset, int limit) throws RpcException {
		if (offset < 0 || offset > MAX_BLOCKS || limit < 1 || limit > MAX_PAGE || (snapshotId == null && offset != 0))
			throw RpcException.badRequest("Invalid structure block page.");
		if (snapshotId != null) return page(level, id, snapshotId, offset, limit);
		String status = readiness();
		if (!"ok".equals(status)) return envelope(level, status);
		Entity entity = lookup(level, id);
		if (entity == null) return envelope(level, "not_found_or_unloaded");
		try {
			Object contraption = bindings.contraption().invoke(entity);
			if (contraption == null) return envelope(level, "not_initialized");
			var map = (Map<?, ?>) bindings.blocks().invoke(contraption);
			JsonObject out = envelope(level, "ok");
			out.addProperty("structureId", id.toString());
			out.addProperty("memberCount", map.size());
			out.add("transform", transform(entity));
			long captureStarted = System.nanoTime();
			var members = new ArrayList<StructureBlockInfo>();
			for (Object value : map.values()) {
				if (members.size() >= MAX_BLOCKS) break;
				members.add((StructureBlockInfo) value);
			}
			members.sort(Comparator.comparing(StructureBlockInfo::pos));
			var records = new ArrayList<String>();
			for (StructureBlockInfo member : members) {
				if (!records.isEmpty() && System.nanoTime() - captureStarted > CAPTURE_BUDGET_NANOS) break;
				BlockPos local = member.pos();
				JsonObject record = Levels.describeBlock(level, local, member.state());
				record.add("worldCenter", vec(global(entity, Vec3.atCenterOf(local))));
				String encoded = record.toString();
				if (encoded.length() <= 2048) records.add(encoded);
			}
			out.addProperty("truncated", records.size() != map.size());
			out.addProperty("capturedCount", records.size());
			return page(level, id, snapshots.put(out.toString(), records), offset, limit);
		} catch (ReflectiveOperationException | LinkageError | IllegalArgumentException e) {
			return envelope(level, "adapter_unavailable");
		}
	}

	private JsonObject page(ServerLevel level, UUID id, String snapshotId, int offset, int limit) throws RpcException {
		var snapshot = snapshots.get(snapshotId);
		if (snapshot == null) return envelope(level, "snapshot_expired_or_missing");
		JsonObject out = JsonParser.parseString(snapshot.metadata).getAsJsonObject();
		if (!out.get("dimension").getAsString().equals(Levels.dimensionId(level))
				|| !out.get("structureId").getAsString().equals(id.toString()))
			throw RpcException.badRequest("Snapshot belongs to a different structure or dimension.");
		if (offset > snapshot.records.size()) throw RpcException.badRequest("Offset outside captured records.");
		int end = Math.min(snapshot.records.size(), offset + limit);
		JsonArray members = new JsonArray();
		for (int i = offset; i < end; i++) members.add(JsonParser.parseString(snapshot.records.get(i)));
		out.add("blocks", members);
		out.addProperty("snapshotId", snapshotId);
		out.addProperty("offset", offset);
		if (end < snapshot.records.size()) out.addProperty("nextOffset", end);
		return out;
	}

	private static JsonObject envelope(ServerLevel level, String status) {
		JsonObject out = new JsonObject();
		out.addProperty("status", status);
		out.addProperty("dimension", Levels.dimensionId(level));
		out.addProperty("gameTime", level.getGameTime());
		out.addProperty("source", "create_6.0.10_server_contraption");
		return out;
	}

	private static JsonObject vec(Vec3 value) {
		if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z))
			throw new IllegalArgumentException("Invalid Create vector");
		JsonObject out = new JsonObject();
		out.addProperty("x", value.x);
		out.addProperty("y", value.y);
		out.addProperty("z", value.z);
		return out;
	}
}
