package dev.mcpfabric.adapters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Bounded, non-mutating reads of the existing Create 6.0.10 static-world kinetic network cache.
 * The caller must use the server thread and must check that the anchor's chunk is loaded.
 */
public final class CreateNetworkAdapter implements KineticNetworkObserver {
	public static final int MAX_PAGE_SIZE = 64;
	public static final int MAX_VISITS = 4096;
	public static final int MAX_SNAPSHOT_MEMBERS = 256;
	private static final long CAPTURE_BUDGET_NANOS = 3_000_000L;
	private static final BoundedSnapshotCache SNAPSHOTS = new BoundedSnapshotCache(8, MAX_SNAPSHOT_MEMBERS, 30_000_000_000L, System::nanoTime);
	private static final String KINETIC = "com.simibubi.create.content.kinetics.base.KineticBlockEntity";
	private static final String NETWORK = "com.simibubi.create.content.kinetics.KineticNetwork";
	private static final String PROPAGATOR = "com.simibubi.create.content.kinetics.TorquePropagator";


	/** A per-tick view. Do not retain this object, its maps or iterators across ticks. */
	static final class NetworkView {
		final Class<?> kinetic, networkClass;
		final Object network, id;
		final Map<?, ?> members, sources;
		NetworkView(Class<?> k, Class<?> n, Object v, Object id, Map<?, ?> m, Map<?, ?> s) {
			this.kinetic = k; this.networkClass = n; this.network = v; this.id = id; this.members = m; this.sources = s;
		}
	}

	static NetworkView resolve(BlockEntity anchor) throws ReflectiveOperationException {
		JsonObject kineticInfo = CreateAdapterProvider.kinetic().inspect(anchor);
		if (!"ok".equals(kineticInfo.get("status").getAsString()) || !kineticInfo.get("hasNetwork").getAsBoolean()) return null;
		Class<?> kinetic = Class.forName(KINETIC), networkClass = Class.forName(NETWORK);
		Field registry = Class.forName(PROPAGATOR).getDeclaredField("networks");
		if (!registry.trySetAccessible()) return null;
		Object id = kinetic.getField("network").get(anchor);
		Object worldsObject = registry.get(null);
		if (!(worldsObject instanceof Map<?, ?> worlds)) return null;
		if (!(worlds.get(anchor.getLevel()) instanceof Map<?, ?> networks)) return null;
		Object network = networks.get(id);
		if (!networkClass.isInstance(network)) return null;
		if (!(networkClass.getField("members").get(network) instanceof Map<?, ?> members)) return null;
		if (!(networkClass.getField("sources").get(network) instanceof Map<?, ?> sources)) return null;
		return new NetworkView(kinetic, networkClass, network, id, members, sources);
	}

	/** O(1) cached totals and member counts; never scans the member map. */
	@Override
	public JsonObject summary(BlockEntity anchor) {
		JsonObject info = CreateAdapterProvider.kinetic().inspect(anchor);
		if (!"ok".equals(info.get("status").getAsString())) return info;
		if (!info.get("hasNetwork").getAsBoolean()) return status("network_not_established", "The anchor has no network.");
		try {
			NetworkView v = resolve(anchor);
			if (v == null) return status("network_unavailable", "The existing network cache is unavailable.");
			JsonObject out = status("ok", "Constant-work cached network summary; values can be stale.");
			out.addProperty("networkId", String.valueOf(v.id));
			out.addProperty("loadedMemberCacheCount", v.members.size());
			out.addProperty("loadedSourceCacheCount", v.sources.size());
			boolean initialized = v.networkClass.getField("initialized").getBoolean(v.network);
			out.addProperty("networkInitialized", initialized);
			if (initialized) readTotals(v.networkClass, v.network, out);
			else out.addProperty("totalsStatus", "unavailable_uninitialized");
			out.add("anchor", info);
			out.addProperty("sampleGameTime", anchor.getLevel().getGameTime());
			out.addProperty("sampleDimension", Levels.dimensionId(anchor.getLevel()));
			return out;
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return status("unavailable", "Network summary unavailable: " + e.getClass().getSimpleName());
		}
	}

	static JsonObject member(NetworkView v, Map.Entry<?, ?> entry) {
		if (!(entry.getKey() instanceof BlockEntity entity) || !v.kinetic.isInstance(entity)) return null;
		JsonObject item = new JsonObject();
		BlockPos p = entity.getBlockPos();
		item.addProperty("x", p.getX()); item.addProperty("y", p.getY()); item.addProperty("z", p.getZ());
		try { item.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString()); }
		catch (RuntimeException | LinkageError e) { item.addProperty("blockIdStatus", "unavailable"); }
		boolean isSource = v.sources.containsKey(entity);
		item.addProperty("isSourceInCache", isSource);
		addMeasured(item, "rpm", v.kinetic, "getSpeed", new Class<?>[0], entity);
		addMeasured(item, "theoreticalRpm", v.kinetic, "getTheoreticalSpeed", new Class<?>[0], entity);
		if (entry.getValue() instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedStressPerRpm", n.doubleValue());
		addMeasured(item, "stressSU", v.networkClass, "getActualStressOf", v.kinetic, v.network, entity);
		Object capacity = v.sources.get(entity);
		if (capacity instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedCapacityPerRpm", n.doubleValue());
		if (isSource) addMeasured(item, "capacitySU", v.networkClass, "getActualCapacityOf", v.kinetic, v.network, entity);
		try {
			Object source = v.kinetic.getField("source").get(entity);
			if (source instanceof BlockPos upstream) {
				JsonObject pos = new JsonObject();
				pos.addProperty("x", upstream.getX()); pos.addProperty("y", upstream.getY()); pos.addProperty("z", upstream.getZ());
				item.add("sourcePosition", pos);
			}
		} catch (ReflectiveOperationException | LinkageError | SecurityException e) { item.addProperty("sourcePositionStatus", "unavailable"); }
		item.addProperty("validity", "cached_member_not_revalidated");
		return item;
	}

	@Override
	public JsonObject inspect(BlockEntity anchor, int offset, int limit) {
		if (!validPage(offset, limit))
			return status("bad_request", "offset must be 0..4095 and limit 1..64.");
		JsonObject anchorStatus = CreateAdapterProvider.kinetic().inspect(anchor);
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
			result.addProperty("snapshotKind", "bounded_single_call_capture");
			result.addProperty("snapshotTtlSeconds", 30);
			result.addProperty("sampleGameTime", anchor.getLevel().getGameTime());
			result.addProperty("sampleDimension", Levels.dimensionId(anchor.getLevel()));
			result.addProperty("sampleX", anchor.getBlockPos().getX());
			result.addProperty("sampleY", anchor.getBlockPos().getY());
			result.addProperty("sampleZ", anchor.getBlockPos().getZ());
			result.addProperty("loadedMemberCacheCount", members.size());
			result.addProperty("loadedSourceCacheCount", sources.size());
			boolean initialized = networkClass.getField("initialized").getBoolean(network);
			result.addProperty("networkInitialized", initialized);
			result.addProperty("anchorNetworkDirty", kinetic.getField("networkDirty").getBoolean(anchor));
			if (initialized) readTotals(networkClass, network, result);
			else result.addProperty("totalsStatus", "unavailable_uninitialized");
			result.add("anchor", anchorStatus);
			if (members.containsKey(anchor)) addMeasured(result, "anchorStressSU", networkClass, "getActualStressOf", kinetic, network, anchor);
			if (sources.containsKey(anchor)) addMeasured(result, "anchorCapacitySU", networkClass, "getActualCapacityOf", kinetic, network, anchor);
			result.addProperty("anchorContributionSource", "Create getActualStressOf/getActualCapacityOf: cached coefficient multiplied by absolute theoretical/generated RPM");
			List<JsonObject> captured = captureMembers(kinetic, networkClass, network, members, sources, result);
			String snapshotId = storeSnapshot(result, captured);
			return page(snapshotId, offset, limit);
		} catch (ReflectiveOperationException | LinkageError | SecurityException | ClassCastException | IllegalArgumentException e) {
			return status("unavailable", "Could not read Create network cache: " + e.getClass().getSimpleName());
		}
	}

	static String storeSnapshot(JsonObject metadata, List<JsonObject> items) {
		List<JsonObject> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingInt((JsonObject o) -> o.get("x").getAsInt())
				.thenComparingInt(o -> o.get("y").getAsInt()).thenComparingInt(o -> o.get("z").getAsInt()));
		List<String> records = new ArrayList<>(sorted.size());
		for (JsonObject item : sorted) records.add(item.toString());
		return SNAPSHOTS.put(metadata.toString(), records);
	}

	static boolean validPage(int offset, int limit) {
		return offset >= 0 && offset < MAX_VISITS && limit >= 1 && limit <= MAX_PAGE_SIZE;
	}

	/** Continue an immutable snapshot; caller must enforce the same read authorization as inspect. */
	@Override
	public JsonObject page(String snapshotId, int offset, int limit) {
		if (!validPage(offset, limit)) return status("bad_request", "offset must be 0..4095 and limit 1..64.");
		BoundedSnapshotCache.Snapshot snapshot = SNAPSHOTS.get(snapshotId);
		if (snapshot == null) return status("snapshot_expired_or_unknown", "Snapshot is missing, expired, or evicted; create a new one.");
		if (offset > snapshot.records.size()) return status("bad_request", "offset exceeds captured member count.");
		JsonObject result = JsonParser.parseString(snapshot.metadata).getAsJsonObject();
		JsonArray members = new JsonArray();
		int end = Math.min(snapshot.records.size(), offset + limit);
		for (int i = offset; i < end; i++) members.add(JsonParser.parseString(snapshot.records.get(i)));
		result.add("members", members);
		result.addProperty("snapshotId", snapshotId);
		result.addProperty("offset", offset);
		result.addProperty("capturedMembers", snapshot.records.size());
		result.addProperty("hasMore", end < snapshot.records.size());
		result.addProperty("continuationAvailable", end < snapshot.records.size());
		if (end < snapshot.records.size()) result.addProperty("nextOffset", end);
		return result;
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

	private static void addMeasured(JsonObject target, String name, Class<?> owner, String method,
			Class<?> parameterType, Object receiver, Object argument) {
		try { addFinite(target, name, owner.getMethod(method, parameterType).invoke(receiver, argument)); }
		catch (ReflectiveOperationException | LinkageError | SecurityException e) { target.addProperty(name + "Status", "unavailable"); }
	}

	private static List<JsonObject> captureMembers(Class<?> kinetic, Class<?> networkClass, Object network, Map<?, ?> members, Map<?, ?> sources,
			JsonObject result) {
		long started = System.nanoTime();
		Iterator<? extends Map.Entry<?, ?>> iterator = members.entrySet().iterator();
		int visited = 0, invalid = 0;
		List<JsonObject> captured = new ArrayList<>();
		while (visited < MAX_VISITS && captured.size() < MAX_SNAPSHOT_MEMBERS && iterator.hasNext()) {
			if (visited > 0 && System.nanoTime() - started >= CAPTURE_BUDGET_NANOS) break;
			Map.Entry<?, ?> entry = iterator.next(); visited++;
			Object value = entry.getKey();
			if (!(value instanceof BlockEntity entity) || !kinetic.isInstance(entity)) { invalid++; continue; }
			JsonObject item = new JsonObject();
			BlockPos p = entity.getBlockPos();
			item.addProperty("x", p.getX()); item.addProperty("y", p.getY()); item.addProperty("z", p.getZ());
			try { item.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString()); }
			catch (RuntimeException | LinkageError e) { item.addProperty("blockIdStatus", "unavailable"); }
			boolean isSource = sources.containsKey(entity);
			item.addProperty("isSourceInCache", isSource);
			addMeasured(item, "rpm", kinetic, "getSpeed", new Class<?>[0], entity);
			addMeasured(item, "theoreticalRpm", kinetic, "getTheoreticalSpeed", new Class<?>[0], entity);
			if (entry.getValue() instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedStressPerRpm", n.doubleValue());
			else item.addProperty("cachedStressPerRpmStatus", "unavailable");
			addMeasured(item, "stressSU", networkClass, "getActualStressOf", kinetic, network, entity);
			Object capacity = sources.get(entity);
			if (capacity instanceof Number n && Double.isFinite(n.doubleValue())) item.addProperty("cachedCapacityPerRpm", n.doubleValue());
			if (isSource) addMeasured(item, "capacitySU", networkClass, "getActualCapacityOf", kinetic, network, entity);
			try {
				Object source = kinetic.getField("source").get(entity);
				if (source instanceof BlockPos upstream) {
					JsonObject pos = new JsonObject();
					pos.addProperty("x", upstream.getX()); pos.addProperty("y", upstream.getY()); pos.addProperty("z", upstream.getZ());
					item.add("sourcePosition", pos);
				}
			} catch (ReflectiveOperationException | LinkageError | SecurityException e) { item.addProperty("sourcePositionStatus", "unavailable"); }
			item.addProperty("validity", "cached_member_not_revalidated");
			captured.add(item);
		}
		boolean truncated = iterator.hasNext();
		result.addProperty("visited", visited);
		result.addProperty("invalidCachedMembers", invalid);
		result.addProperty("scanBudgetExhausted", truncated);
		result.addProperty("memberDetailsIncomplete", truncated || invalid > 0);
		result.addProperty("captureBudgetMs", 3);
		result.addProperty("captureBudgetKind", "soft; checked between members, a slow accessor or sorting can exceed it");
		result.addProperty("captureElapsedMs", (System.nanoTime() - started) / 1_000_000.0);
		result.addProperty("ordering", "Captured subset sorted by coordinates; truncation may omit arbitrary cached members.");
		return captured;
	}

	private static void addMeasured(JsonObject target, String name, Class<?> owner, String method,
			Class<?>[] parameterTypes, Object receiver, Object... args) {
		try { addFinite(target, name, owner.getMethod(method, parameterTypes).invoke(receiver, args)); }
		catch (ReflectiveOperationException | LinkageError | SecurityException e) { target.addProperty(name + "Status", "unavailable"); }
	}

	private static JsonObject status(String code, String message) {
		JsonObject result = new JsonObject();
		result.addProperty("status", code); result.addProperty("message", message);
		return result;
	}
}
