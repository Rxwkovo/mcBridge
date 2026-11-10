package dev.mcpfabric.telemetry;

import com.google.gson.*;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.adapters.CreateAdapterProvider;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import dev.mcpfabric.bridge.WorkAreaPolicy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Server-thread state; journal and history disk work run on background threads. */
public final class FactoryTelemetry {
	private static final int MAX_MONITORS = 16, MAX_VOLUME = 64, MAX_HISTORY = 64;
	private static final Map<String, Monitor> MONITORS = new LinkedHashMap<>();
	private static FactoryMeter meter;
	private static MinecraftServer owner;
	private static TelemetryJournal journal;
	private static TelemetryJournal previousJournal;
	private static volatile String policyFailure;
	private static int replayIndex;
	private static boolean recovered, failureNoted;
	private static long sequence, seenDrops;
	private static final java.util.concurrent.ThreadPoolExecutor HISTORY_WORKER = new java.util.concurrent.ThreadPoolExecutor(
			1, 1, 0, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8), r -> {
				Thread t = new Thread(r, "mechagent-history-reader"); t.setDaemon(true); return t;
			});
	private record HistoryRequest(long created, java.util.concurrent.CompletableFuture<JsonObject> result) {}
	private static final Map<String, HistoryRequest> historyRequests = new HashMap<>();
	private static final class Monitor {
		String id, name, area, dimension;
		TelemetryPolicy.Box box;
		int interval, cursor;
		boolean capabilities, kinetic, moving, paused;
		boolean historyTruncated;
		long nextTick;
		final ArrayDeque<JsonObject> history = new ArrayDeque<>();
		final Map<String, Map<String, Long>> baselines = new HashMap<>();
		final Map<String, Long> baselineTicks = new HashMap<>();
		final Map<String, JsonObject> pointMetrics = new LinkedHashMap<>();
		final Map<String, JsonObject> kineticPoints = new LinkedHashMap<>();
		JsonObject lastMetrics = TelemetryMetrics.inventoryNet(null, null, false);
	}
	private FactoryTelemetry() {}

	/** Called on server thread once per new world; the journal loads asynchronously. */
	public static void start(MinecraftServer server) {
		stop(); owner = server; policyFailure = null; recovered = false; failureNoted = false; replayIndex = 0; sequence = 0; seenDrops = 0;
		Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		journal = new TelemetryJournal(world, previousJournal);
		meter = new FactoryMeter(server, journal, () -> ++sequence);
	}
	public static void stop() {
		for (HistoryRequest request : historyRequests.values()) request.result.cancel(true);
		historyRequests.clear();
		if (journal != null) { journal.close(); previousJournal = journal; }
		owner = null; journal = null; MONITORS.clear(); meter = null;
	}

	private static TelemetryPolicy.Box box(JsonObject min, JsonObject max) {
		return new TelemetryPolicy.Box(min.get("x").getAsInt(), min.get("y").getAsInt(), min.get("z").getAsInt(),
				max.get("x").getAsInt(), max.get("y").getAsInt(), max.get("z").getAsInt());
	}
	private static boolean validId(String s) { return s != null && s.matches("[A-Za-z0-9_.-]{1,64}"); }
	private static boolean authorized(String id, String dimension, TelemetryPolicy.Box box) {
		return owner != null && box.valid(MAX_VOLUME) && WorkAreaPolicy.contains(owner, id, dimension,
				box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
	}
	/** Caller must enforce management authorization; this class independently checks trusted bounds. */
	public static JsonObject create(String name, String areaId, String dimension, BlockPos min, BlockPos max,
			int intervalTicks, boolean includeCapabilities, boolean includeKinetic, boolean includeMovingStructures) {
		return define(null, name, areaId, dimension, min, max, intervalTicks, includeCapabilities, includeKinetic, includeMovingStructures);
	}
	public static JsonObject update(String id, String name, String areaId, String dimension, BlockPos min, BlockPos max,
			int intervalTicks, boolean includeCapabilities, boolean includeKinetic, boolean includeMovingStructures) {
		Monitor old = MONITORS.get(id);
		if (old == null || !authorized(old.area, old.dimension, old.box)) return error("not_found", "No authorized monitor with that id.");
		return define(id, name, areaId, dimension, min, max, intervalTicks, includeCapabilities, includeKinetic, includeMovingStructures);
	}
	private static JsonObject define(String existing, String name, String area, String dimension, BlockPos min, BlockPos max,
			int interval, boolean capabilities, boolean kinetic, boolean moving) {
		if (!isReady()) return unavailable();
		if (name == null || name.isBlank() || name.length() > 64 || !validId(area) || dimension == null || dimension.length() > 128
				|| min == null || max == null || interval < 20 || interval > 1200) return error("bad_request", "Invalid monitor parameters.");
		TelemetryPolicy.Box b = new TelemetryPolicy.Box(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
		if (!authorized(area, dimension, b)) return error("forbidden", "Bounds are not within the trusted server work area or exceed 64 blocks.");
		if (existing == null && MONITORS.size() >= MAX_MONITORS) return error("capacity", "Monitor limit reached.");
		JsonObject record = event("define", existing == null ? UUID.randomUUID().toString() : existing, 0);
		record.addProperty("name", name); record.addProperty("area", area); record.addProperty("dimension", dimension);
		record.add("min", position(min)); record.add("max", position(max)); record.addProperty("interval", interval);
		record.addProperty("capabilities", capabilities); record.addProperty("kinetic", kinetic); record.addProperty("moving", moving);
		if (!journal.append(record)) return error("persistence_unavailable", "Monitor definition was not queued.");
		apply(record, false);
		if (existing != null) gap(MONITORS.get(existing), "configuration_changed", 0);
		JsonObject result = describe(MONITORS.get(record.get("monitorId").getAsString()));
		result.addProperty("persistence", "queued_not_yet_durable"); return result;
	}
	public static JsonObject pause(String id, boolean paused) {
		if (!isReady()) return unavailable(); Monitor m = MONITORS.get(id);
		if (m == null || !authorized(m.area, m.dimension, m.box)) return error("not_found", "No authorized monitor with that id.");
		JsonObject record = event("pause", id, 0); record.addProperty("paused", paused);
		if (!journal.append(record)) return error("persistence_unavailable", "Pause change was not queued.");
		m.paused = paused; gap(m, paused ? "paused" : "resumed", 0);
		JsonObject result = describe(m); result.addProperty("persistence", "queued_not_yet_durable"); return result;
	}
	public static JsonObject list() {
		if (!isReady()) return unavailable(); JsonObject out = health(); JsonArray a = new JsonArray();
		for (Monitor m : MONITORS.values()) if (authorized(m.area, m.dimension, m.box)) a.add(describe(m)); out.add("monitors", a); return out;
	}
	public static JsonObject get(String id) {
		if (!isReady()) return unavailable(); Monitor m = MONITORS.get(id);
		return m == null || !authorized(m.area, m.dimension, m.box) ? error("not_found", "No authorized monitor with that id.") : describe(m);
	}
	public static JsonObject history(String id, long sinceSequence, int limit) {
		if (!isReady()) return unavailable(); Monitor m = MONITORS.get(id);
		if (m == null || !authorized(m.area, m.dimension, m.box)) return error("not_found", "No authorized monitor with that id.");
		if (sinceSequence < 0 || limit < 1 || limit > 100) return error("bad_request", "Invalid sequence or limit.");
		JsonObject out = health(); JsonArray entries = new JsonArray(); boolean more = false;
		for (JsonObject e : m.history) if (e.get("sequence").getAsLong() > sinceSequence) {
			if (entries.size() >= limit) { more = true; break; } entries.add(e.deepCopy());
		}
		out.add("entries", entries); out.addProperty("hasMore", more);
		out.addProperty("historyTruncated", m.historyTruncated);
		if (!m.history.isEmpty()) out.addProperty("earliestRetainedSequence", m.history.peekFirst().get("sequence").getAsLong());
		out.addProperty("historyScope", "last 64 retained events per monitor; older events may be omitted");
		return out;
	}
	public static JsonObject metrics(String id) {
		if (!isReady()) return unavailable(); Monitor m = MONITORS.get(id);
		if (m == null || !authorized(m.area, m.dimension, m.box)) return error("not_found", "No authorized monitor with that id.");
		JsonObject out = health(); out.add("metrics", m.lastMetrics.deepCopy());
		JsonArray points = new JsonArray(); for (JsonObject point : m.pointMetrics.values()) points.add(point.deepCopy());
		JsonArray kinetics = new JsonArray(); for (JsonObject point : m.kineticPoints.values()) kinetics.add(point.deepCopy());
		out.add("points", points); out.add("kineticPoints", kinetics);
		out.addProperty("scope", "last sampled interval per position; not an atomic snapshot");
		return out;
	}
	/** Starts or polls a bounded disk aggregation; no disk access occurs on the server thread. */
	public static JsonObject metricsWindow(String id, Integer seconds, String fromText, String toText,
			String basis, Long fromTickValue, Long toTickValue) {
		if (!isReady()) return unavailable(); Monitor m=MONITORS.get(id);
		if(m==null||!authorized(m.area,m.dimension,m.box))return error("not_found","No authorized monitor with that id.");
		if(!Set.of("wall","game_ticks").contains(basis))return error("bad_request","timeBasis must be wall or game_ticks.");
		boolean ticks=basis.equals("game_ticks"); Instant from,to;long fromTick=0,toTick=0;
		try {
			if(ticks){
				if(fromText!=null||toText!=null)return error("bad_request","UTC bounds apply only to wall time.");
				if((fromTickValue==null)!=(toTickValue==null))return error("bad_request","Provide both tick bounds.");
				if(fromTickValue!=null){if(seconds!=null)return error("bad_request","Choose tick bounds or windowSeconds.");fromTick=fromTickValue;toTick=toTickValue;}
				else{int s=seconds==null?60:seconds;if(s<1||s>86400)return error("bad_request","windowSeconds must be 1..86400.");
					toTick=Levels.resolve(owner,m.dimension).getGameTime();fromTick=Math.max(0,toTick-s*20L);}
				if(fromTick<0||toTick<=fromTick||toTick-fromTick>1_728_000L)return error("bad_request","Invalid tick range (maximum 1728000 ticks).");
				from=Instant.EPOCH;to=Instant.now();
			}else{
				if(fromTickValue!=null||toTickValue!=null)return error("bad_request","Tick bounds apply only to game_ticks.");
				if((fromText==null)!=(toText==null))return error("bad_request","Provide both UTC bounds.");
				if(fromText!=null){if(seconds!=null)return error("bad_request","Choose UTC bounds or windowSeconds.");from=Instant.parse(fromText);to=Instant.parse(toText);}
				else{int s=seconds==null?60:seconds;if(s<1||s>86400)return error("bad_request","windowSeconds must be 1..86400.");to=Instant.now();from=to.minusSeconds(s);}
				if(!from.isBefore(to)||java.time.Duration.between(from,to).compareTo(java.time.Duration.ofHours(24))>0)return error("bad_request","Invalid UTC range (maximum 24 hours).");
			}
		}catch(Exception ex){return error("bad_request","Invalid time range or unavailable dimension.");}
		Path data=journal.dataDirectory();if(data==null)return unavailable();
		long now=System.nanoTime();historyRequests.entrySet().removeIf(e->now-e.getValue().created>60_000_000_000L);
		String key="window|"+id+"|"+m.area+"|"+m.dimension+"|"+m.box+"|"+seconds+"|"+fromText+"|"+toText+"|"+basis+"|"+fromTickValue+"|"+toTickValue;
		HistoryRequest existing=historyRequests.get(key);
		if(existing!=null){if(!existing.result.isDone())return error("pending","Aggregation running; repeat the same request.");
			historyRequests.remove(key);try{return existing.result.join().deepCopy();}catch(RuntimeException e){return error("unavailable","Aggregation failed; inspect journal integrity.");}}
		if(historyRequests.size()>=8)return error("busy","History worker capacity reached.");
		TelemetryWindow window=new TelemetryWindow(id,m.area,m.dimension,m.box,from,to,fromTick,toTick,ticks,m.interval);
		java.util.concurrent.CompletableFuture<JsonObject> future=new java.util.concurrent.CompletableFuture<>();
		try{HISTORY_WORKER.execute(()->{if(future.isCancelled())return;try{future.complete(TelemetryWindow.read(data,window));}catch(Exception e){future.completeExceptionally(e);}});}
		catch(java.util.concurrent.RejectedExecutionException e){return error("busy","History worker is full.");}
		historyRequests.put(key,new HistoryRequest(now,future));return error("pending","Aggregation started; repeat the same request.");
	}
	/** Compare two explicit UTC periods, with separate non-atomic scans. */
	public static JsonObject comparePeriods(String id,String firstFrom,String firstTo,String secondFrom,String secondTo){
		if(!isReady())return unavailable();Monitor m=MONITORS.get(id);
		if(m==null||!authorized(m.area,m.dimension,m.box))return error("not_found","No authorized monitor with that id.");
		Instant a,b,c,d;try{a=Instant.parse(firstFrom);b=Instant.parse(firstTo);c=Instant.parse(secondFrom);d=Instant.parse(secondTo);
			if(!a.isBefore(b)||!c.isBefore(d)||java.time.Duration.between(a,b).compareTo(java.time.Duration.ofHours(24))>0||java.time.Duration.between(c,d).compareTo(java.time.Duration.ofHours(24))>0)return error("bad_request","Each UTC range must be positive and at most 24 hours.");
		}catch(RuntimeException e){return error("bad_request","Invalid ISO-8601 UTC bounds.");}
		Path data=journal.dataDirectory();if(data==null)return unavailable();long now=System.nanoTime();historyRequests.entrySet().removeIf(e->now-e.getValue().created>60_000_000_000L);
		String key="compare|"+id+"|"+m.area+"|"+m.dimension+"|"+m.box+"|"+firstFrom+"|"+firstTo+"|"+secondFrom+"|"+secondTo;
		HistoryRequest existing=historyRequests.get(key);if(existing!=null){if(!existing.result.isDone())return error("pending","Comparison running; repeat the same request.");
			historyRequests.remove(key);try{return existing.result.join().deepCopy();}catch(RuntimeException e){return error("unavailable","Comparison failed; inspect journal integrity.");}}
		if(historyRequests.size()>=8)return error("busy","History worker capacity reached.");
		TelemetryWindow first=new TelemetryWindow(id,m.area,m.dimension,m.box,a,b,0,0,false,m.interval),second=new TelemetryWindow(id,m.area,m.dimension,m.box,c,d,0,0,false,m.interval);
		java.util.concurrent.CompletableFuture<JsonObject> future=new java.util.concurrent.CompletableFuture<>();
		try{HISTORY_WORKER.execute(()->{if(future.isCancelled())return;try{future.complete(TelemetryComparison.compare(TelemetryWindow.read(data,first),TelemetryWindow.read(data,second)));}catch(Exception e){future.completeExceptionally(e);}});}
		catch(java.util.concurrent.RejectedExecutionException e){return error("busy","History worker is full.");}
		historyRequests.put(key,new HistoryRequest(now,future));return error("pending","Comparison started; repeat the same request.");
	}
	/** Disk reads run off-thread. Repeating the same request polls its bounded result. */
	public static JsonObject historyFromDisk(String id, long since, int limit, String fromText, String toText, String type) {
		if (!isReady()) return unavailable(); Monitor m = MONITORS.get(id);
		if (m == null || !authorized(m.area, m.dimension, m.box)) return error("not_found", "No authorized monitor with that id.");
		if (since < 0 || limit < 1 || limit > 100 || (type != null && !Set.of("sample", "gap").contains(type))) return error("bad_request", "Invalid history filter.");
		Instant from, to;
		try {
			from = fromText == null ? null : Instant.parse(fromText); to = toText == null ? null : Instant.parse(toText);
			if (from != null && to != null && from.isAfter(to)) return error("bad_request", "Invalid time range.");
		} catch (RuntimeException e) { return error("bad_request", "Time values must be ISO-8601 instants."); }
		Path data = journal.dataDirectory(); if (data == null) return unavailable();
		long now = System.nanoTime(); historyRequests.entrySet().removeIf(e -> now - e.getValue().created > 60_000_000_000L);
		String key = id + "|" + m.area + "|" + m.dimension + "|" + m.box + "|" + since + "|" + limit + "|" + fromText + "|" + toText + "|" + type;
		HistoryRequest existing = historyRequests.get(key);
		if (existing != null) {
			if (!existing.result.isDone()) return error("pending", "History scan is running; repeat the same request.");
			try { return existing.result.join().deepCopy(); }
			catch (RuntimeException e) { return error("unavailable", "History scan failed; inspect journal integrity."); }
		}
		if (historyRequests.size() >= 8) return error("busy", "History scan capacity reached.");
		String area = m.area, dimension = m.dimension; TelemetryPolicy.Box bounds = m.box;
		java.util.concurrent.CompletableFuture<JsonObject> future = new java.util.concurrent.CompletableFuture<>();
		try {
			HISTORY_WORKER.execute(() -> {
				if (future.isCancelled()) return;
				try { future.complete(TelemetryHistory.read(data, id, area, dimension, bounds, since, limit, from, to, type)); }
				catch (Exception e) { future.completeExceptionally(e); }
			});
		} catch (java.util.concurrent.RejectedExecutionException e) { return error("busy", "History worker is full."); }
		historyRequests.put(key, new HistoryRequest(now, future));
		return error("pending", "History scan started; repeat the same request.");
	}

	public static JsonObject createMeter(String area, String dimension, BlockPos hopper, String item) {
		return isReady() ? meter.createMeter(area, dimension, hopper, item) : unavailable();
	}

	public static JsonObject queryMeter(String id) {
		return isReady() ? meter.queryMeter(id) : unavailable();
	}

	public static JsonObject stopMeter(String id) {
		return isReady() ? meter.stopMeter(id) : unavailable();
	}

	public static boolean meterCandidate(MinecraftServer server, String dim, BlockPos hopper) {
		return isReady() && meter.meterCandidate(server, dim, hopper);
	}

	public static void recordMeter(MinecraftServer server, String dim, BlockPos hopper, String item, long tick) {
		if (isReady()) meter.recordMeter(server, dim, hopper, item, tick);
	}

	/** Kinetic projection of a raw sample page; nextSequence always advances over raw samples. */
	public static JsonObject kineticHistory(String id, long since, int limit, String from, String to) {
		JsonObject page=historyFromDisk(id,since,limit,from,to,"sample");
		if(!page.has("status")||!"ok".equals(page.get("status").getAsString()))return page;
		JsonArray projected=new JsonArray();
		for(JsonElement element:page.getAsJsonArray("entries")) {
			JsonObject raw=element.getAsJsonObject(); if(!raw.has("kinetic"))continue;
			JsonObject point=new JsonObject();
			for(String key:List.of("sequence","position","gameTime","wallTimeUtc","blockId","kinetic"))if(raw.has(key))point.add(key,raw.get(key).deepCopy());
			if(raw.get("kinetic").isJsonObject()) point.add("kinetic",TelemetryMetrics.kineticWithCachedUtilization(raw.getAsJsonObject("kinetic")));
			projected.add(point);
		}
		page.add("entries",projected);
		page.addProperty("paginationNote","limit and cursor apply to raw samples; a page may contain fewer kinetic entries than limit.");
		return page;
	}
	/** Called once per server tick; Visits at most four positions; policy is refreshed before sampling. */
	public static void tick(MinecraftServer server) {
		if (server != owner || journal == null || policyFailure != null) return;
		if (!journal.ready()) {
			if (recovered && journal.failure() != null && !failureNoted) {
				failureNoted = true;
				for (Monitor m : MONITORS.values()) gap(m, "journal_failure_" + journal.failure(), 0);
			}
			return;
		}
		if (!recovered) {
			List<JsonObject> events = journal.recovered(); int processed = 0;
			while (replayIndex < events.size() && processed++ < 64) {
				try { apply(events.get(replayIndex++), true); }
				catch (RuntimeException e) { policyFailure = "Invalid persisted telemetry event; recovery refused."; return; }
			}
			if (replayIndex < events.size()) return;
			sequence = Math.max(sequence, journal.recoveredLastSequence());
			for (Monitor m : MONITORS.values()) m.historyTruncated |= journal.recoveredHistoryTruncated(m.id);
			meter.noteRestartGap();
			recovered = true;
			for (Monitor m : MONITORS.values()) gap(m, "restart_observation_gap", 0);
			if (journal.quarantinedTailBytes() > 0)
				for (Monitor m : MONITORS.values()) gap(m, "incomplete_journal_tail_quarantined", 0);
		}
		long drops = journal.dropped();
		if (drops != seenDrops || journal.failure() != null) {
			if (drops != seenDrops) {
				seenDrops = drops;
				for (Monitor m : MONITORS.values()) gap(m, "journal_queue_loss", 0);
			}
			if (journal.failure() != null) for (Monitor m : MONITORS.values()) clearMetrics(m);
		}
		int budget = 4; long started = System.nanoTime();
		for (Monitor m : MONITORS.values()) {
			if (budget == 0 || System.nanoTime() - started > 1_000_000L) break;
			if (m.paused) continue;
			try {
				ServerLevel level = Levels.resolve(server, m.dimension); long tick = level.getGameTime();
				if (tick < m.nextTick || !authorized(m.area, m.dimension, m.box)) continue;
				BlockPos pos = positionAt(m.box, m.cursor++); budget--;
				if (!level.hasChunkAt(pos)) gap(m, "unloaded_chunk", tick);
				else sample(m, level, pos, tick, m.moving && m.cursor == 1);
				if (m.cursor >= m.box.volume()) { m.cursor = 0; m.nextTick = tick + m.interval; }
			} catch (Exception | LinkageError e) { gap(m, "sample_error_" + e.getClass().getSimpleName(), 0); }
		}
	}

	private static void sample(Monitor m, ServerLevel level, BlockPos pos, long tick, boolean sampleMoving) {
		JsonObject e = event("sample", m.id, tick); e.add("position", position(pos));
		var state = level.getBlockState(pos); e.addProperty("blockId", Levels.blockId(state));
		e.add("blockState", Levels.describeBlock(level, pos, state));
		e.addProperty("source", "server-thread world state and optional unsided capabilities");
		if (sampleMoving) {
			var b = m.box;
			var bounds = new net.minecraft.world.phys.AABB(b.minX(), b.minY(), b.minZ(),
					(double) b.maxX() + 1, (double) b.maxY() + 1, (double) b.maxZ() + 1);
			e.add("movingStructures", CreateAdapterProvider.moving().sampleArea(level, bounds));
		}
		if (m.kinetic) {
			JsonObject kinetic = TelemetryMetrics.kineticWithCachedUtilization(CreateAdapterProvider.kinetic().inspect(level.getBlockEntity(pos)));
			e.add("kinetic", kinetic); JsonObject point = new JsonObject();
			point.add("position", position(pos)); point.addProperty("sampleGameTime", tick); point.add("kinetic", kinetic.deepCopy());
			m.kineticPoints.put(pos.toShortString(), point);
		}
		if (m.capabilities) {
			JsonObject capabilities = McpFabric.platform().inspectBlockCapabilities(level, pos);
			e.add("capabilities", capabilities);
			Map<String, Long> current = inventory(capabilities);
			String key = pos.toShortString(); Map<String, Long> previous = m.baselines.get(key);
			m.lastMetrics = TelemetryMetrics.inventoryNet(previous, current, current != null);
			m.lastMetrics.add("position", position(pos)); m.lastMetrics.addProperty("sampleGameTime", tick);
			Long priorTick = m.baselineTicks.get(key);
			if (priorTick != null && tick >= priorTick && previous != null && current != null) m.lastMetrics.addProperty("intervalTicks", tick - priorTick);
			if (current == null) { m.baselines.remove(key); m.baselineTicks.remove(key); }
			else { m.baselines.put(key, current); m.baselineTicks.put(key, tick); }
			m.pointMetrics.put(key, m.lastMetrics.deepCopy());
			e.add("inventoryObservation", m.lastMetrics.deepCopy());
		}
		append(m, e);
	}
	private static Map<String, Long> inventory(JsonObject caps) {
		try {
			JsonObject items = caps.getAsJsonObject("items");
			if (items == null || !"available".equals(items.get("status").getAsString())
					|| (items.has("truncated") && items.get("truncated").getAsBoolean())) return null;
			Map<String, Long> counts = new HashMap<>(); JsonArray contents = items.getAsJsonArray("contents");
			if (contents == null || contents.size() > 64) return null;
			for (JsonElement element : contents) {
				JsonObject entry = element.getAsJsonObject(); String id = entry.get("id").getAsString(); long count = entry.get("count").getAsLong();
				if (id.length() > 256 || count < 0) return null;
				counts.put(id, Math.addExact(counts.getOrDefault(id, 0L), count));
			}
			return Map.copyOf(counts);
		} catch (RuntimeException e) { return null; }
	}
	private static BlockPos positionAt(TelemetryPolicy.Box b, int i) {
		int dx = b.maxX() - b.minX() + 1, dz = b.maxZ() - b.minZ() + 1;
		return new BlockPos(b.minX() + (i / dz) % dx, b.minY() + i / (dx * dz), b.minZ() + i % dz);
	}
	private static void gap(Monitor m, String reason, long tick) {
		clearMetrics(m);
		JsonObject e = event("gap", m.id, tick); e.addProperty("reason", reason); append(m, e);
	}
	private static void append(Monitor m, JsonObject e) {
		if (!journal.append(e)) clearMetrics(m);
		remember(m, e);
	}
	private static void remember(Monitor m, JsonObject e) {
		while (m.history.size() >= MAX_HISTORY) { m.history.removeFirst(); m.historyTruncated = true; }
		m.history.addLast(e.deepCopy());
	}
	private static void clearMetrics(Monitor m) {
		m.baselines.clear(); m.baselineTicks.clear(); m.pointMetrics.clear(); m.kineticPoints.clear();
		m.lastMetrics = TelemetryMetrics.inventoryNet(null, null, false);
	}
	private static JsonObject event(String type, String id, long tick) {
		JsonObject e = new JsonObject(); e.addProperty("type", type); e.addProperty("monitorId", id);
		e.addProperty("sequence", ++sequence); e.addProperty("gameTime", tick); e.addProperty("wallTimeUtc", Instant.now().toString()); return e;
	}
	private static void apply(JsonObject e, boolean replay) {
		String type = e.get("type").getAsString();
		if ("meter_state".equals(type)) {
			meter.recover(e);
			sequence = Math.max(sequence, e.get("sequence").getAsLong()); return;
		}
		String id = e.get("monitorId").getAsString();
		sequence = Math.max(sequence, e.get("sequence").getAsLong());
		if ("define".equals(type)) {
			String area = e.get("area").getAsString(), dim = e.get("dimension").getAsString();
			TelemetryPolicy.Box b = box(e.getAsJsonObject("min"), e.getAsJsonObject("max"));
			if (!b.valid(MAX_VOLUME) || (!MONITORS.containsKey(id) && MONITORS.size() >= MAX_MONITORS)) return;
			Monitor old = MONITORS.get(id);
			Monitor m = new Monitor(); m.id=id; m.name=e.get("name").getAsString(); m.area=area; m.dimension=dim; m.box=b;
			if (old != null) {
				m.paused = old.paused;
				if (old.area.equals(area) && old.dimension.equals(dim) && old.box.equals(b)) {
					m.history.addAll(old.history); m.historyTruncated = old.historyTruncated;
				} else m.historyTruncated = !old.history.isEmpty() || old.historyTruncated;
			}
			m.interval=e.get("interval").getAsInt(); m.capabilities=e.get("capabilities").getAsBoolean(); m.kinetic=e.get("kinetic").getAsBoolean(); m.moving=e.has("moving") && e.get("moving").getAsBoolean();
			if (m.interval < 20 || m.interval > 1200) throw new IllegalArgumentException("Invalid interval");
			MONITORS.put(id, m);
		} else if ("pause".equals(type)) {
			Monitor m = MONITORS.get(id); if (m != null) { m.paused=e.get("paused").getAsBoolean(); clearMetrics(m); }
		} else if ("sample".equals(type) || "gap".equals(type)) {
			Monitor m = MONITORS.get(id); if (m != null) remember(m, e);
		} else throw new IllegalArgumentException("Unknown event");
	}
	private static JsonObject position(BlockPos p) { JsonObject o = new JsonObject(); o.addProperty("x", p.getX()); o.addProperty("y", p.getY()); o.addProperty("z", p.getZ()); return o; }
	private static JsonObject describe(Monitor m) {
		JsonObject o = health(); o.addProperty("monitorId", m.id); o.addProperty("name", m.name); o.addProperty("areaId", m.area);
		o.addProperty("dimension", m.dimension); o.addProperty("intervalTicks", m.interval); o.addProperty("paused", m.paused);
		o.addProperty("includeCapabilities", m.capabilities); o.addProperty("includeKinetic", m.kinetic); o.addProperty("includeMovingStructures", m.moving);
		o.add("min", position(new BlockPos(m.box.minX(), m.box.minY(), m.box.minZ())));
		o.add("max", position(new BlockPos(m.box.maxX(), m.box.maxY(), m.box.maxZ()))); return o;
	}
	private static boolean isReady() { return owner != null && journal != null && policyFailure == null && recovered; }
	private static JsonObject unavailable() {
		String reason = policyFailure != null ? policyFailure : journal != null && journal.failure() != null ? journal.failure() : "initializing_or_stopped";
		return error("unavailable", reason);
	}
	private static JsonObject health() {
		JsonObject o = new JsonObject(); o.addProperty("status", journal != null && journal.failure() != null ? "degraded" : "ok");
		o.addProperty("droppedPersistenceRecords", journal == null ? 0 : journal.dropped());
		o.addProperty("persistence", journal != null && journal.failure() == null ? "asynchronous" : "failed");
		if (journal != null) {
			o.addProperty("journalBytes", journal.bytesOnDisk());
			o.addProperty("journalQuotaBytes", journal.diskQuotaBytes());
			o.addProperty("quarantinedTailBytes", journal.quarantinedTailBytes());
			// Acceptance and enqueue are one operation in this implementation; these are
			// cumulative process-local counters, not per-record durability receipts.
			o.addProperty("acceptedRecordsThisRun", journal.queuedCount());
			o.addProperty("queuedRecordsThisRun", journal.queuedCount());
			o.addProperty("persistedRecordsThisRun", journal.persistedCount());
			o.addProperty("durableRecordsThisRun", journal.durableCount());
			o.addProperty("notYetPersistedRecordsThisRun", Math.max(0, journal.queuedCount() - journal.persistedCount()));
			o.addProperty("notYetDurableRecordsThisRun", Math.max(0, journal.persistedCount() - journal.durableCount()));
			if (journal.failure() != null) o.addProperty("persistenceError", journal.failure());
		}
		return o;
	}
	private static JsonObject error(String code, String message) { JsonObject o = new JsonObject(); o.addProperty("status", code); o.addProperty("message", message); return o; }
}
