package dev.mcpfabric.construction;

import com.google.gson.*;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.*;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.*;
import dev.mcpfabric.bridge.WorkAreaPolicy.Area;
import java.util.*;

/** Restricted, non-atomic region editing. All state and world access is on the server thread. */
public final class ConstructionService {
	private static final int MAX_EDITS = 128, PER_TICK = 1, MAX_RECORDS = 512;
	private static final long PREVIEW_TTL_TICKS = 1_200L;
	private static final Map<String, Job> previews = new LinkedHashMap<>();
	private static final Map<String, Job> operations = new LinkedHashMap<>();
	private static final Map<String, Receipt> receipts = new LinkedHashMap<>();
	private static MinecraftServer owner;
	private static Path ledger;
	private ConstructionService() {}

	private record Edit(BlockPos pos, BlockState before, BlockState after) {}
	private record Receipt(String key, String signature, String operationId) {}
	private static final class Job {
		final String id, area, dimension, policy;
		final List<Edit> edits;
		final List<String> results = new ArrayList<>();
		final List<String> progress = new ArrayList<>();
		final Set<Integer> applied = new LinkedHashSet<>();
		String status = "preview", operationId;
		int cursor;
		final long previewTick;
		Long startedTick, finishedTick;
		Job(String id, Area area, List<Edit> edits, long previewTick) {
			this.id = id; this.area = area.id(); this.dimension = area.dimension();
			this.policy = area.fingerprint(); this.edits = edits; this.previewTick = previewTick;
			for (int i = 0; i < edits.size(); i++) { results.add("pending"); progress.add("pending"); }
		}
	}

	public static void register(RpcRouter router) {
		router.register("construction.preview", ctx -> call(server -> preview(server, ctx)));
		router.register("construction.commit", ctx -> call(server -> commit(server, ctx)));
		router.register("construction.query", ctx -> call(server -> query(server, ctx)));
		router.register("construction.undo", ctx -> call(server -> undo(server, ctx)));
	}
	private interface Work { JsonElement run(MinecraftServer server) throws RpcException; }
	private static JsonElement call(Work work) throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> work.run(server));
	}
	private static void permit() throws RpcException {
		Gates.notPaused();
		String mode = McpFabric.config().accessMode;
		if (!"REGION_BUILD".equals(mode) && !("ADMIN".equals(mode) && McpFabric.config().allowAdmin))
			throw RpcException.unavailable("Region construction is disabled by accessMode.");
	}
	private static void initialize(MinecraftServer server) throws RpcException {
		if (owner == server) return;
		previews.clear(); operations.clear(); receipts.clear(); owner = null;
		try {
			ledger = server.getWorldPath(LevelResource.ROOT).toRealPath().resolve("data/mechagent-construction-ledger.json");
			if (Files.exists(ledger)) {
				if (Files.size(ledger) > 512_000) throw new IllegalStateException("Ledger exceeds size limit");
				JsonArray entries = JsonParser.parseString(Files.readString(ledger)).getAsJsonArray();
				if (entries.size() > MAX_RECORDS) throw new IllegalStateException("Ledger exceeds record limit");
				for (JsonElement element : entries) {
					JsonObject o = element.getAsJsonObject();
					Receipt r = new Receipt(o.get("key").getAsString(), o.get("signature").getAsString(), o.get("operationId").getAsString());
					if (!ConstructionPolicy.validKey(r.key()) || receipts.putIfAbsent(r.key(), r) != null) throw new IllegalStateException("Invalid ledger");
				}
			}
			owner = server;
		} catch (Exception e) { throw RpcException.unavailable("Construction ledger unavailable: " + e.getClass().getSimpleName()); }
	}
	private static void remember(Receipt receipt) throws RpcException {
		if (receipts.size() >= MAX_RECORDS) throw RpcException.unavailable("Construction ledger is full; no new operations accepted.");
		Map<String, Receipt> next = new LinkedHashMap<>(receipts); next.put(receipt.key(), receipt);
		JsonArray array = new JsonArray();
		for (Receipt r : next.values()) {
			JsonObject o = new JsonObject(); o.addProperty("key", r.key()); o.addProperty("signature", r.signature()); o.addProperty("operationId", r.operationId()); array.add(o);
		}
		Path tmp = ledger.resolveSibling(ledger.getFileName() + ".tmp");
		try {
			Files.createDirectories(ledger.getParent());
			byte[] contents = array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			if (contents.length > 131_072) throw new IllegalStateException("Ledger size limit reached");
			try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				var buffer = java.nio.ByteBuffer.wrap(contents);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			Files.move(tmp, ledger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			receipts.put(receipt.key(), receipt);
		} catch (Exception e) { throw RpcException.unavailable("Could not durably record the idempotency key; operation refused."); }
	}
	private static Path journalPath(String operationId) throws RpcException {
		try {
			String normalized = UUID.fromString(operationId).toString();
			if (!normalized.equals(operationId)) throw new IllegalArgumentException();
			return ledger.getParent().resolve("mechagent-construction").resolve(normalized + ".json");
		} catch (Exception ex) { throw RpcException.badRequest("Invalid operationId."); }
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static JsonObject stateJson(BlockState state) {
		JsonObject result = new JsonObject(), properties = new JsonObject();
		result.addProperty("blockId", Levels.blockId(state));
		for (Map.Entry<Property<?>, Comparable<?>> e : state.getValues().entrySet()) {
			Property property = e.getKey(); properties.addProperty(property.getName(), property.getName(e.getValue()));
		}
		result.add("properties", properties); return result;
	}
	private static void writeJournal(Job job) throws RpcException {
		JsonObject root = new JsonObject(); root.addProperty("schema", 1); root.addProperty("operationId", job.operationId);
		root.addProperty("areaId", job.area); root.addProperty("dimension", job.dimension); root.addProperty("policy", job.policy);
		root.addProperty("status", job.status); root.addProperty("previewTick", job.previewTick); root.addProperty("cursor", job.cursor);
		if (job.startedTick != null) root.addProperty("startedTick", job.startedTick);
		if (job.finishedTick != null) root.addProperty("finishedTick", job.finishedTick);
		JsonArray items = new JsonArray();
		for (int i = 0; i < job.edits.size(); i++) {
			Edit e = job.edits.get(i); JsonObject item = new JsonObject();
			item.addProperty("x", e.pos().getX()); item.addProperty("y", e.pos().getY()); item.addProperty("z", e.pos().getZ());
			item.add("before", stateJson(e.before())); item.add("after", stateJson(e.after()));
			item.addProperty("progress", job.progress.get(i)); item.addProperty("result", job.results.get(i));
			items.add(item);
		}
		root.add("items", items); byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (bytes.length > 65_536) throw RpcException.unavailable("Construction journal exceeds limit.");
		Path target = journalPath(job.operationId), tmp = target.resolveSibling(target.getFileName() + ".tmp");
		try {
			Files.createDirectories(target.getParent());
			try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				var buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) ch.write(buffer); ch.force(true);
			}
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception ex) { throw RpcException.unavailable("Construction journal write failed; no further edits will execute."); }
	}
	private static JsonElement preview(MinecraftServer server, RpcContext ctx) throws RpcException {
		permit(); initialize(server);
		String areaId = ctx.getString("areaId"), dimension = ctx.getString("dimension");
		if (!ConstructionPolicy.validKey(areaId)) throw RpcException.badRequest("Invalid areaId.");
		Area area = WorkAreaPolicy.require(server, areaId, dimension);
		ServerLevel level = Levels.resolve(server, dimension);
		long now = level.getGameTime();
		previews.entrySet().removeIf(entry -> entry.getValue().status.equals("preview") && entry.getValue().dimension.equals(dimension)
				&& (now < entry.getValue().previewTick || now - entry.getValue().previewTick > PREVIEW_TTL_TICKS));
		JsonElement raw = ctx.params().get("operations");
		if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty() || raw.getAsJsonArray().size() > area.maxBatch()) throw RpcException.badRequest("operations exceed the configured batch limit.");
		if (previews.size() >= MAX_RECORDS) throw RpcException.unavailable("Preview limit reached.");
		List<Edit> edits = new ArrayList<>(); Set<BlockPos> positions = new HashSet<>();
		for (JsonElement element : raw.getAsJsonArray()) {
			if (!element.isJsonObject()) throw RpcException.badRequest("Each operation must be an object.");
			JsonObject obj = element.getAsJsonObject(); RpcContext p = new RpcContext("operation", obj);
			BlockPos pos = new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z"));
			if (!positions.add(pos)) throw RpcException.badRequest("Duplicate coordinates.");
			if (!area.bounds().contains(pos.getX(), pos.getY(), pos.getZ()) || !level.isInWorldBounds(pos)) throw RpcException.badRequest("Position outside authorized area or world bounds.");
			if (!level.hasChunkAt(pos)) throw RpcException.unavailable("Position is in an unloaded chunk.");
			BlockState before = level.getBlockState(pos), after = state(p.getString("blockId"), p.optObject("properties"), area);
			validateExisting(level, pos, before, area);
			if (obj.has("expected")) {
				if (!obj.get("expected").isJsonObject()) throw RpcException.badRequest("expected must be a state object.");
				RpcContext expect = new RpcContext("expected", obj.getAsJsonObject("expected"));
				if (!before.equals(state(expect.getString("blockId"), expect.optObject("properties"), area))) throw RpcException.badRequest("Expected state conflict at " + pos.toShortString());
			}
			edits.add(new Edit(pos, before, after));
		}
		String id = UUID.randomUUID().toString(); Job job = new Job(id, area, edits, level.getGameTime()); previews.put(id, job);
		JsonObject result = describe(job); result.addProperty("previewId", id); result.addProperty("gameTime", level.getGameTime());
		result.addProperty("warning", "Non-atomic edits; existing block entities are protected and placed block entities cannot be undone by this interface. Shaft data and kinetic network state are not restored by undo.");
		return result;
	}
	private static BlockState state(String id, JsonObject properties, Area area) throws RpcException {
		if (!ConstructionPolicy.allowedBlock(id, area.allowedBlocks())) throw RpcException.badRequest("Block is not in construction allowlist.");
		ResourceLocation key = ResourceLocation.tryParse(id);
		if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) throw RpcException.badRequest("Block is not registered.");
		Block block = BuiltInRegistries.BLOCK.get(key); BlockState state = block.defaultBlockState();
		if (properties != null) for (Map.Entry<String, JsonElement> e : properties.entrySet()) {
			if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) throw RpcException.badRequest("State properties must be strings.");
			Property<?> property = block.getStateDefinition().getProperty(e.getKey());
			if (property == null) throw RpcException.badRequest("Unknown state property: " + e.getKey());
			state = setProperty(state, property, e.getValue().getAsString());
		}
		return state;
	}
	private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String value) throws RpcException {
		Optional<T> parsed = property.getValue(value);
		if (parsed.isEmpty()) throw RpcException.badRequest("Invalid state property value.");
		return state.setValue(property, parsed.get());
	}
	private static void validateExisting(ServerLevel level, BlockPos pos, BlockState state, Area area) throws RpcException {
		String id = Levels.blockId(state);
		if (!ConstructionPolicy.allowedBlock(id, area.allowedBlocks())) throw RpcException.badRequest("Existing block is outside construction allowlist.");
		if (level.getBlockEntity(pos) != null && !"create:shaft".equals(id)) throw RpcException.badRequest("Block entity cannot be edited.");
	}
	private static JsonElement commit(MinecraftServer server, RpcContext ctx) throws RpcException {
		permit(); initialize(server); String id = ctx.getString("previewId"), key = ctx.getString("idempotencyKey");
		if (!ConstructionPolicy.validKey(key)) throw RpcException.badRequest("Invalid idempotencyKey.");
		String signature = "commit:" + id; Receipt old = receipts.get(key);
		if (old != null) return repeat(server, old, signature);
		Job job = previews.get(id);
		if (job == null) throw RpcException.notFound("Preview not found.");
		if (!job.status.equals("preview")) throw RpcException.badRequest("Preview has already been committed.");
		Area area = WorkAreaPolicy.require(server, job.area, job.dimension);
		if (!area.fingerprint().equals(job.policy)) throw RpcException.unavailable("Work area configuration changed; create a new preview.");
		long age = Levels.resolve(server, job.dimension).getGameTime() - job.previewTick;
		if (age < 0 || age > PREVIEW_TTL_TICKS) throw RpcException.unavailable("Preview expired; create a new preview.");
		for (Job active : operations.values()) if (active.status.equals("running") || active.status.equals("undo_running")) throw RpcException.unavailable("Another edit is active.");
		ServerLevel commitLevel = Levels.resolve(server, job.dimension);
		for (Edit edit : job.edits) {
			if (!area.bounds().contains(edit.pos().getX(), edit.pos().getY(), edit.pos().getZ()) || !commitLevel.hasChunkAt(edit.pos()) || !commitLevel.getBlockState(edit.pos()).equals(edit.before()))
				throw RpcException.unavailable("Preview conflict or unloaded position; no edits were started.");
			validateExisting(commitLevel, edit.pos(), edit.before(), area);
		}
		String op = UUID.randomUUID().toString();
		job.operationId = op; job.status = "running"; job.startedTick = Levels.resolve(server, job.dimension).getGameTime();
		try { writeJournal(job); remember(new Receipt(key, signature, op)); }
		catch (RpcException e) { job.operationId = null; job.status = "preview"; job.startedTick = null; throw e; }
		operations.put(op, job);
		return describe(job);
	}
	private static JsonElement repeat(MinecraftServer server, Receipt receipt, String signature) throws RpcException {
		if (!receipt.signature().equals(signature)) throw RpcException.badRequest("idempotencyKey was used for a different request.");
		Job job = operations.get(receipt.operationId());
		if (job != null) {
			Area current = WorkAreaPolicy.require(server, job.area, job.dimension);
			if (!current.fingerprint().equals(job.policy)) throw RpcException.unavailable("Work area configuration changed.");
			return describe(job);
		}
		JsonObject o = new JsonObject(); o.addProperty("operationId", receipt.operationId()); o.addProperty("status", "recovery_required"); return o;
	}
	private static JsonElement query(MinecraftServer server, RpcContext ctx) throws RpcException {
		Gates.vision(); initialize(server); String id = ctx.getString("operationId"); Job job = operations.get(id);
		if (job != null) {
			Area current = WorkAreaPolicy.require(server, job.area, job.dimension);
			if (!current.fingerprint().equals(job.policy)) throw RpcException.unavailable("Work area configuration changed.");
			return describe(job);
		}
		for (Receipt r : receipts.values()) if (r.operationId().equals(id)) return recover(server, id);
		throw RpcException.notFound("Operation not found.");
	}
	private static JsonElement recover(MinecraftServer server, String operationId) throws RpcException {
		Path path = journalPath(operationId);
		if (!Files.exists(path)) {
			JsonObject old = new JsonObject(); old.addProperty("operationId", operationId); old.addProperty("status", "recovery_required");
			old.addProperty("detail", "Legacy receipt has no operation journal."); return old;
		}
		try {
			if (!Files.isRegularFile(path) || Files.size(path) > 65_536) throw new IllegalArgumentException();
			JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
			if (root.get("schema").getAsInt() != 1 || !operationId.equals(root.get("operationId").getAsString())) throw new IllegalArgumentException();
			String areaId = root.get("areaId").getAsString(), dimension = root.get("dimension").getAsString();
			Area current = WorkAreaPolicy.require(server, areaId, dimension);
			if (!current.fingerprint().equals(root.get("policy").getAsString())) throw RpcException.unavailable("Work area configuration changed.");
			ServerLevel level = Levels.resolve(server, dimension); JsonArray entries = root.getAsJsonArray("items");
			if (entries.isEmpty() || entries.size() > MAX_EDITS) throw new IllegalArgumentException();
			String journalStatus = root.get("status").getAsString();
			if (!Set.of("running", "completed", "partial", "interrupted", "undo_running", "undone").contains(journalStatus)) throw new IllegalArgumentException();
			JsonArray recovered = new JsonArray(); Set<BlockPos> seen = new HashSet<>();
			for (JsonElement element : entries) {
				JsonObject item = element.getAsJsonObject(); RpcContext p = new RpcContext("journal", item);
				BlockPos pos = new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z"));
				if (!seen.add(pos) || !current.bounds().contains(pos.getX(), pos.getY(), pos.getZ()) || !level.isInWorldBounds(pos)) throw new IllegalArgumentException();
				RpcContext before = new RpcContext("before", p.getObject("before")), after = new RpcContext("after", p.getObject("after"));
				BlockState from = state(before.getString("blockId"), before.optObject("properties"), current);
				BlockState to = state(after.getString("blockId"), after.optObject("properties"), current);
				boolean loaded = level.hasChunkAt(pos); BlockState observed = loaded ? level.getBlockState(pos) : null;
				JsonObject output = new JsonObject(); output.addProperty("x", pos.getX()); output.addProperty("y", pos.getY()); output.addProperty("z", pos.getZ());
				output.add("before", stateJson(from)); output.add("after", stateJson(to));
				String progress = p.getString("progress"), result = p.getString("result");
				if (!Set.of("pending", "apply_intent", "undo_intent", "applied", "undone", "unloaded_or_outside", "state_conflict", "not_changed").contains(progress)
						|| !Set.of("pending", "applied", "undone", "unloaded_or_outside", "state_conflict", "not_changed").contains(result)) throw new IllegalArgumentException();
				output.addProperty("journalProgress", progress); output.addProperty("journalResult", result);
				output.addProperty("observation", ConstructionPolicy.classifyRecovery(loaded, loaded && observed.equals(from), loaded && observed.equals(to)));
				recovered.add(output);
			}
			JsonObject out = new JsonObject(); out.addProperty("operationId", operationId); out.addProperty("status", "recovery_required");
			out.addProperty("journalStatus", journalStatus); out.addProperty("gameTime", level.getGameTime());
			out.addProperty("undoRequested", receipts.values().stream().anyMatch(r -> r.operationId().equals(operationId) && r.signature().equals("undo:" + operationId)));
			out.addProperty("note", "Observations show current state, not who changed it; no blocks were loaded or edits replayed.");
			out.add("items", recovered); return out;
		} catch (RpcException ex) { throw ex; }
		catch (Exception ex) { throw RpcException.unavailable("Construction journal unavailable or invalid."); }
	}
	private static JsonElement undo(MinecraftServer server, RpcContext ctx) throws RpcException {
		permit(); initialize(server); String id = ctx.getString("operationId"), key = ctx.getString("idempotencyKey");
		if (!ConstructionPolicy.validKey(key)) throw RpcException.badRequest("Invalid idempotencyKey.");
		String signature = "undo:" + id; Receipt old = receipts.get(key);
		if (old != null) return repeat(server, old, signature);
		Job job = operations.get(id);
		if (job == null) throw RpcException.unavailable("Operation cannot be recovered after restart.");
		if (!job.status.equals("completed") && !job.status.equals("interrupted") && !job.status.equals("partial")) throw RpcException.badRequest("Operation is not ready for undo.");
		for (Job active : operations.values()) if (active.status.equals("running") || active.status.equals("undo_running")) throw RpcException.unavailable("Another edit is active.");
		Area area = WorkAreaPolicy.require(server, job.area, job.dimension);
		if (!area.fingerprint().equals(job.policy)) throw RpcException.unavailable("Work area configuration changed.");
		ServerLevel level = Levels.resolve(server, job.dimension);
		for (int index : job.applied) {
			Edit edit = job.edits.get(index);
			if (!level.hasChunkAt(edit.pos())) throw RpcException.unavailable("Undo target is unloaded; no undo was started.");
			if (level.getBlockState(edit.pos()).equals(edit.after())) validateExisting(level, edit.pos(), edit.after(), area);
		}
		remember(new Receipt(key, signature, id));
		job.cursor = job.edits.size() - 1; job.status = "undo_running"; job.startedTick = Levels.resolve(server, job.dimension).getGameTime(); job.finishedTick = null;
		try { writeJournal(job); } catch (RpcException e) { job.status = "interrupted"; throw e; }
		return describe(job);
	}
	public static void tick(MinecraftServer server) {
		if (owner != server) return;
		int budget = PER_TICK;
		for (Job job : operations.values()) {
			if (budget == 0) break;
			boolean undo = job.status.equals("undo_running");
			if (!undo && !job.status.equals("running")) continue;
			try {
				permit(); Area current = WorkAreaPolicy.require(server, job.area, job.dimension);
				if (!current.fingerprint().equals(job.policy)) throw RpcException.unavailable("Work area changed.");
				ServerLevel level = Levels.resolve(server, job.dimension);
				while (budget > 0 && (undo ? job.cursor >= 0 : job.cursor < job.edits.size())) {
					int index = job.cursor; job.cursor += undo ? -1 : 1; budget--;
					if (undo && !job.applied.contains(index)) continue;
					Edit e = job.edits.get(index); String result;
					if (!current.bounds().contains(e.pos().getX(), e.pos().getY(), e.pos().getZ()) || !level.hasChunkAt(e.pos())) result = "unloaded_or_outside";
					else if (!level.getBlockState(e.pos()).equals(undo ? e.after() : e.before())) result = "state_conflict";
					else {
						validateExisting(level, e.pos(), level.getBlockState(e.pos()), current);
						job.progress.set(index, undo ? "undo_intent" : "apply_intent");
						writeJournal(job);
						if (level.setBlock(e.pos(), undo ? e.before() : e.after(), 3)) {
							result = undo ? "undone" : "applied";
							if (!undo) job.applied.add(index);
						} else result = "not_changed";
					}
					job.results.set(index, result);
					job.progress.set(index, result);
					writeJournal(job);
				}
				if (undo ? job.cursor < 0 : job.cursor >= job.edits.size()) {
					job.status = ConstructionPolicy.completedStatus(undo, job.results);
					job.finishedTick = level.getGameTime();
					writeJournal(job);
				}
			} catch (Exception ex) {
				job.status = "interrupted"; job.finishedTick = server.overworld().getGameTime();
				try { writeJournal(job); } catch (RpcException ignored) { }
			}
		}
	}
	public static void clear(MinecraftServer server) {
		if (owner == server) { owner = null; previews.clear(); operations.clear(); receipts.clear(); }
	}
	private static JsonObject describe(Job job) {
		JsonObject o = new JsonObject(); o.addProperty("previewId", job.id);
		if (job.operationId != null) o.addProperty("operationId", job.operationId);
		o.addProperty("status", job.status); o.addProperty("areaId", job.area); o.addProperty("dimension", job.dimension);
		o.addProperty("previewTick", job.previewTick);
		if (job.startedTick != null) o.addProperty("startedTick", job.startedTick);
		if (job.finishedTick != null) o.addProperty("finishedTick", job.finishedTick);
		JsonArray items = new JsonArray();
		for (int i = 0; i < job.edits.size(); i++) {
			Edit e = job.edits.get(i); JsonObject item = new JsonObject();
			item.addProperty("x", e.pos().getX()); item.addProperty("y", e.pos().getY()); item.addProperty("z", e.pos().getZ());
			item.addProperty("before", e.before().toString()); item.addProperty("after", e.after().toString()); item.addProperty("result", job.results.get(i)); items.add(item);
		}
		o.add("items", items); return o;
	}
}
