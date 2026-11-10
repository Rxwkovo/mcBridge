package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.WorkAreaPolicy;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Server-thread transfer counter; the shared asynchronous journal owns disk durability. */
final class FactoryMeter {
	private static final int MAX_METERS = 16;
	private final Map<String, JsonObject> meters = new LinkedHashMap<>();
	private final MinecraftServer owner;
	private final TelemetryJournal journal;
	private final LongSupplier sequence;

	FactoryMeter(MinecraftServer owner, TelemetryJournal journal, LongSupplier sequence) {
		this.owner = owner;
		this.journal = journal;
		this.sequence = sequence;
	}

	void recover(JsonObject state) {
		String id = state.get("meterId").getAsString();
		if (!meters.containsKey(id) && meters.size() >= MAX_METERS) throw new IllegalArgumentException("Too many meters");
		meters.put(id, state.deepCopy());
	}

	void noteRestartGap() {
		for (JsonObject meter : meters.values()) {
			if (!meter.get("stopped").getAsBoolean()) meter.addProperty("coverage", "partial_restart_gap");
		}
	}

	private boolean authorized(String area, String dimension, TelemetryPolicy.Box bounds) {
		return bounds.valid(64) && WorkAreaPolicy.contains(owner, area, dimension,
				bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}

	/** One explicitly scoped Create depot -> vanilla hopper output boundary. */
	JsonObject createMeter(String area, String dimension, BlockPos hopper, String item) {
		if (!McpFabric.platform().supportsFactoryMeter()) return error("unsupported", "Factory Meter requires NeoForge 21.1.248 and Create 6.0.10.");
		if (area == null || !area.matches("[A-Za-z0-9_.-]{1,64}") || dimension == null || dimension.length() > 128 || hopper == null || item == null || item.length() > 256 || !item.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) return error("bad_request", "Invalid meter parameters.");
		BlockPos source = hopper.above();
		TelemetryPolicy.Box b = new TelemetryPolicy.Box(hopper.getX(), hopper.getY(), hopper.getZ(), source.getX(), source.getY(), source.getZ());
		if (!authorized(area, dimension, b)) return error("forbidden", "Both depot and hopper must be in the authorized area.");
		if (meters.size() >= MAX_METERS) return error("capacity", "Meter limit reached.");
		for (JsonObject old : meters.values()) if (!old.get("stopped").getAsBoolean() && old.get("dimension").getAsString().equals(dimension) && old.getAsJsonObject("hopper").equals(position(hopper))) return error("conflict", "A meter already covers this hopper.");
		try {
			ServerLevel level = Levels.resolve(owner, dimension);
			if (!level.hasChunkAt(hopper) || !level.hasChunkAt(source)) return error("unavailable", "Chunk is not loaded.");
			if (!"minecraft:hopper".equals(Levels.blockId(level.getBlockState(hopper))) || !"create:depot".equals(Levels.blockId(level.getBlockState(source)))) return error("not_applicable", "Expected vanilla hopper beneath Create depot.");
		} catch (Exception ex) { return error("unavailable", "Dimension unavailable."); }
		JsonObject state = new JsonObject(); state.addProperty("type", "meter_state"); state.addProperty("meterId", UUID.randomUUID().toString());
		state.addProperty("area", area); state.addProperty("dimension", dimension); state.add("hopper", position(hopper)); state.addProperty("itemId", item);
		state.addProperty("count", 0L); state.addProperty("stopped", false); state.addProperty("coverage", "partial_hook_not_yet_observed");
		state.addProperty("createdUtc", Instant.now().toString()); state.addProperty("source", "create_depot_to_vanilla_hopper");
		if (!saveMeter(state)) return error("persistence_unavailable", "Meter definition was not queued.");
		return meterView(state);
	}
	private static TelemetryPolicy.Box meterBounds(JsonObject m) {
		JsonObject p=m.getAsJsonObject("hopper"); int x=p.get("x").getAsInt(), y=p.get("y").getAsInt(), z=p.get("z").getAsInt();
		return new TelemetryPolicy.Box(x,y,z,x,y+1,z);
	}
	private boolean meterAuthorized(JsonObject m) { return authorized(m.get("area").getAsString(),m.get("dimension").getAsString(),meterBounds(m)); }
	private boolean saveMeter(JsonObject state) {
		state.addProperty("sequence", sequence.getAsLong());
		if (!journal.append(state)) return false;
		meters.put(state.get("meterId").getAsString(), state.deepCopy()); return true;
	}
	private JsonObject meterView(JsonObject state) {
		JsonObject out=state.deepCopy(); out.addProperty("status",journal.failure()==null?"ok":"degraded");
		out.addProperty("persistence","queued_async_not_per_record_durable"); out.addProperty("droppedPersistenceRecords",journal.dropped());
		out.addProperty("measurement","measured_throughput_at_depot_to_hopper_boundary");
		out.addProperty("limitations","Not machine production or end-to-end output. Restart, unloaded chunks and unavailable observation create gaps."); return out;
	}
	JsonObject queryMeter(String id) {
		JsonObject m=meters.get(id);
		return m==null || !meterAuthorized(m)?error("not_found","No authorized meter with that id."):meterView(m);
	}
	JsonObject stopMeter(String id) {
		JsonObject m=meters.get(id);
		if (m==null || !meterAuthorized(m)) return error("not_found","No authorized meter with that id.");
		JsonObject next=m.deepCopy(); next.addProperty("stopped",true); next.addProperty("coverage","partial_stopped");
		if (!saveMeter(next)) return error("persistence_unavailable","Stop was not queued."); return meterView(next);
	}
	/** Called exclusively on server thread. Does not load chunks. */
	boolean meterCandidate(MinecraftServer server, String dim, BlockPos hopper) {
		if (server!=owner || !server.isSameThread()) return false;
		for (JsonObject m:meters.values()) if (!m.get("stopped").getAsBoolean() && m.get("dimension").getAsString().equals(dim) && m.getAsJsonObject("hopper").equals(position(hopper)) && meterAuthorized(m)) return true;
		return false;
	}
	void recordMeter(MinecraftServer server, String dim, BlockPos hopper, String item, long tick) {
		if (server != owner || !server.isSameThread()) return;
		for (JsonObject m : meters.values()) {
			if (m.get("stopped").getAsBoolean() || !m.get("dimension").getAsString().equals(dim) || !m.getAsJsonObject("hopper").equals(position(hopper)) || !m.get("itemId").getAsString().equals(item) || !meterAuthorized(m)) continue;
			JsonObject next=m.deepCopy(); try { next.addProperty("count",Math.addExact(m.get("count").getAsLong(),1)); } catch (ArithmeticException ex) { m.addProperty("coverage","partial_counter_overflow"); return; }
			if ("partial_hook_not_yet_observed".equals(next.get("coverage").getAsString())) next.addProperty("coverage", "partial_boundary_only");
			next.addProperty("lastGameTick",tick); next.addProperty("lastTransferUtc",Instant.now().toString());
			if (!saveMeter(next)) { m.addProperty("coverage","partial_persistence_failure"); m.addProperty("count",next.get("count").getAsLong()); }
			return;
		}
	}

	private static JsonObject position(BlockPos pos) {
		var out = new JsonObject();
		out.addProperty("x", pos.getX());
		out.addProperty("y", pos.getY());
		out.addProperty("z", pos.getZ());
		return out;
	}

	private static JsonObject error(String code, String message) {
		var out = new JsonObject();
		out.addProperty("status", code);
		out.addProperty("message", message);
		return out;
	}
}
