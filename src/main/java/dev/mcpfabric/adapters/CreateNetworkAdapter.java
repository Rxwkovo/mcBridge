package dev.mcpfabric.adapters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

/** Bounded, non-mutating reads of the existing Create 6.0.10 static-world kinetic network cache.
 * The caller must use the server thread and must check that the anchor's chunk is loaded.
 */
public final class CreateNetworkAdapter {
	public static final int MAX_PAGE_SIZE = 64;
	public static final int MAX_VISITS = 4096;
	private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private static final String NETWORK = "com.simibubi.create.content.kinetics.KineticNetwork";
	private static final String PROPAGATOR = "com.simibubi.create.content.kinetics.TorquePropagator";

	private CreateNetworkAdapter() {}

	public static JsonObject inspect(BlockEntity anchor, int offset, int limit) {
		if (!validPage(offset, limit))
			return status("bad_request", "offset must be 0..4095 and limit 1..64.");
		JsonObject anchorStatus = CreateKineticAdapter.inspect(anchor);
		if (!"ok".equals(anchorStatus.get("status").getAsString())) return anchorStatus;
		if (!anchorStatus.get("hasNetwork").getAsBoolean()) return status("network_not_established", "The anchor has no network.");
		try {
			Class<?> kinetic = Class.forName(KINETIC), networkClass = Class.forName(NETWORK);
			Field allNetworks = Class.forName(PROPAGATOR).getDeclaredField("networks");
			if (!allNetworks.trySetAccessible()) return status("unavailable", "The Create network registry is not accessible.");
			Object id = kinetic.getField("network").get(anchor);
			Object worldMaps = allNetworks.get(null);
			if (!(worldMaps instanceof Map<?, ?> worlds)) return status("unavailable", "Unexpected network registry.");
			Object worldNetworks = worlds.get(anchor.getLevel());
			if (!(worldNetworks instanceof Map<?, ?> networks)) return status("network_unavailable", "No existing network registry for this world.");
			Object network = networks.get(id);
			if (!networkClass.isInstance(network)) return status("network_unavailable", "The anchor's network cache does not exist.");
			Object membersValue = networkClass.getField("members").get(network);
			Object sourcesValue = networkClass.getField("sources").get(network);
			if (!(membersValue instanceof Map<?, ?> members) || !(sourcesValue instanceof Map<?, ?> sources))
				return status("unavailable", "Unexpected network member maps.");
			JsonObject result = status("ok", "Bounded view of cached Create network state; no network is created or recalculated.");
			result.addProperty("networkId", String.valueOf(id));
			result.addProperty("snapshotKind", "live_cache_not_atomic_across_calls");
			result.addProperty("ordering", "HashMap iteration order can change; pages may repeat or omit members if the network changes.");
			result.addProperty("loadedMemberCacheCount", members.size());
			result.addProperty("loadedSourceCacheCount", sources.size());
			boolean initialized = networkClass.getField("initialized").getBoolean(network);
			result.addProperty("networkInitialized", initialized);
			result.addProperty("anchorNetworkDirty", kinetic.getField("networkDirty").getBoolean(anchor));
			if (initialized) readTotals(networkClass, network, result);
			else result.addProperty("totalsStatus", "unavailable_uninitialized");
			result.add("anchor", anchorStatus);
			if (members.containsKey(anchor)) addFinite(result, "anchorStressSU", networkClass.getMethod("getActualStressOf", kinetic).invoke(network, anchor));
			if (sources.containsKey(anchor)) addFinite(result, "anchorCapacitySU", networkClass.getMethod("getActualCapacityOf", kinetic).invoke(network, anchor));
			result.addProperty("anchorContributionSource", "Create getActualStressOf/getActualCapacityOf: cached coefficient multiplied by absolute theoretical/generated RPM");
			readPage(kinetic, networkClass, network, members, sources, offset, limit, result);
			return result;
		} catch (ReflectiveOperationException | LinkageError | SecurityException | ClassCastException e) {
			return status("unavailable", "Could not read Create network cache: " + e.getClass().getSimpleName());
		}
	}

	static boolean validPage(int offset, int limit) {
		return offset >= 0 && offset < MAX_VISITS && limit >= 1 && limit <= MAX_PAGE_SIZE;
	}

	static void readTotals(Class<?> networkClass, Object network, JsonObject result) {
		try {
			Field capacity = declared(networkClass, "currentCapacity"), stress = declared(networkClass, "currentStress");
			Field unloadedCapacity = declared(networkClass, "unloadedCapacity"), unloadedStress = declared(networkClass, "unloadedStress");
			Field unloadedMembers = declared(networkClass, "unloadedMembers");
			if (capacity == null || stress == null || unloadedCapacity == null || unloadedStress == null || unloadedMembers == null) {
				result.addProperty("totalsStatus", "unavailable"); return;
			}
			float c = capacity.getFloat(network), s = stress.getFloat(network);
			float uc = unloadedCapacity.getFloat(network), us = unloadedStress.getFloat(network);
			int unloadedCount = unloadedMembers.getInt(network);
			if (!Float.isFinite(c) || !Float.isFinite(s) || !Float.isFinite(uc) || !Float.isFinite(us)) {
				result.addProperty("totalsStatus", "invalid_data"); return;
			}
			result.addProperty("totalsStatus", "cached_may_be_stale");
			result.addProperty("capacitySU", c); result.addProperty("stressSU", s);
			result.addProperty("remainingSU", (double) c - s);
			if (c > 0) result.addProperty("utilization", (double) s / c);
			else result.addProperty("utilizationStatus", "unavailable_nonpositive_capacity");
			result.addProperty("unloadedCapacitySU", uc); result.addProperty("unloadedStressSU", us);
			result.addProperty("unloadedMembers", unloadedCount);
			result.addProperty("totalsSource", "Create KineticNetwork currentCapacity/currentStress; Create includes unloaded contributions");
		} catch (ReflectiveOperationException | SecurityException e) {
			result.addProperty("totalsStatus", "unavailable");
		}
	}

	private static Field declared(Class<?> type, String name) throws NoSuchFieldException {
		Field field = type.getDeclaredField(name);
		return field.trySetAccessible() ? field : null;
	}

	private static void addFinite(JsonObject target, String name, Object value) {
		if (value instanceof Number n && Double.isFinite(n.doubleValue())) target.addProperty(name, n.doubleValue());
		else target.addProperty(name + "Status", "invalid_data");
	}

	private static void readPage(Class<?> kinetic, Class<?> networkClass, Object network, Map<?, ?> members, Map<?, ?> sources,
			int offset, int limit, JsonObject result) throws ReflectiveOperationException {
		Iterator<? extends Map.Entry<?, ?>> iterator = members.entrySet().iterator();
		int visited = 0;
		while (visited < offset && iterator.hasNext()) { iterator.next(); visited++; }
		JsonArray page = new JsonArray();
		Method speed = kinetic.getMethod("getSpeed");
		Method theoretical = kinetic.getMethod("getTheoreticalSpeed");
		Method stressOf = networkClass.getMethod("getActualStressOf", kinetic);
		Method capacityOf = networkClass.getMethod("getActualCapacityOf", kinetic);
		Field sourceField = kinetic.getField("source");
		while (visited < MAX_VISITS && page.size() < limit && iterator.hasNext()) {
			Map.Entry<?, ?> entry = iterator.next(); visited++;
			Object value = entry.getKey();
			if (!(value instanceof BlockEntity entity) || !kinetic.isInstance(entity)) continue;
			JsonObject item = new JsonObject();
			BlockPos p = entity.getBlockPos();
			item.addProperty("x", p.getX()); item.addProperty("y", p.getY()); item.addProperty("z", p.getZ());
			item.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString());
			item.addProperty("isSourceInCache", sources.containsKey(entity));
			float rpm = ((Number) speed.invoke(entity)).floatValue();
			if (Float.isFinite(rpm)) item.addProperty("rpm", rpm); else item.addProperty("rpmStatus", "invalid_data");
			addFinite(item, "theoreticalRpm", theoretical.invoke(entity));
			if (entry.getValue() instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedStressPerRpm", n.doubleValue());
			addFinite(item, "stressSU", stressOf.invoke(network, entity));
			Object capacity = sources.get(entity);
			if (capacity instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedCapacityPerRpm", n.doubleValue());
			if (sources.containsKey(entity)) addFinite(item, "capacitySU", capacityOf.invoke(network, entity));
			Object source = sourceField.get(entity);
			if (source instanceof BlockPos upstream) {
				JsonObject pos = new JsonObject();
				pos.addProperty("x", upstream.getX()); pos.addProperty("y", upstream.getY()); pos.addProperty("z", upstream.getZ());
				item.add("sourcePosition", pos);
			}
			item.addProperty("validity", "cached_member_not_revalidated");
			page.add(item);
		}
		boolean more = iterator.hasNext();
		result.add("members", page);
		result.addProperty("offset", offset); result.addProperty("visited", visited);
		result.addProperty("hasMore", more);
		result.addProperty("continuationAvailable", more && visited < MAX_VISITS);
		if (more && visited < MAX_VISITS) result.addProperty("nextOffset", visited);
		result.addProperty("scanBudgetExhausted", more && visited >= MAX_VISITS);
		result.addProperty("memberDetailsIncomplete", more || offset != 0);
	}

	private static JsonObject status(String code, String message) {
		JsonObject result = new JsonObject();
		result.addProperty("status", code); result.addProperty("message", message);
		return result;
	}
}
