package dev.mcpfabric.adapters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcpfabric.handlers.support.Levels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Server-thread-owned, bounded incremental snapshots. All public methods must run on the server thread. */
public final class CreateNetworkJobs {
	public static final int DEFAULT_TARGET = 4096, MAX_TARGET = 16384;
	private static final int MAX_JOBS = 4, MAX_RECORDS = 16384;
	private static final int MAX_RESTARTS = 3, MAX_RECORD_CHARS = 2048;
	private static final long TTL_NANOS = 120_000_000_000L;
	private static int memberLimit = DEFAULT_TARGET, nodesPerTick = 128;
	private static long globalBudgetBytes = 32L * 1024 * 1024, tickNanos = 1_000_000L;
	private static final Map<String, Job> JOBS = new LinkedHashMap<>();
	private static long usedBytes;
	private static int usedRecords;
	private static String lastScheduled;

	private record Key(int x, int y, int z) implements Comparable<Key> {
		@Override public int compareTo(Key b) {
			int c = Integer.compare(x, b.x); if (c != 0) return c;
			c = Integer.compare(y, b.y); return c != 0 ? c : Integer.compare(z, b.z);
		}
	}

	private static final class Job {
		final String id, dimension, networkId;
		final BlockPos anchor;
		final int target;
		final long createdNanos, startTick;
		final JsonObject initialSummary;
		final TreeMap<Key, String> records = new TreeMap<>();
		String state = "running", reason = "sampling";
		long endTick, bytes;
		int visited, initialMemberCount;
		boolean changeDetected;
		final FailFastCursor cursor = new FailFastCursor(MAX_RESTARTS);
		Job(String id, String dimension, String networkId, BlockPos anchor, int target, long tick, JsonObject summary, int count) {
			this.id=id; this.dimension=dimension; this.networkId=networkId; this.anchor=anchor.immutable(); this.target=target;
			this.createdNanos=System.nanoTime(); this.startTick=tick; this.endTick=tick; this.initialSummary=summary; this.initialMemberCount=count;
		}
		void stop(String state, String reason) { this.state=state; this.reason=reason; cursor.clear(); }
	}

	private CreateNetworkJobs() {}

	/** Apply trusted administrator limits on the server thread, before starting jobs. */
	public static void configure(int members, int micros, int nodes, long bytes) {
		memberLimit = Math.max(1, Math.min(MAX_TARGET, members));
		nodesPerTick = Math.max(1, Math.min(1024, nodes));
		tickNanos = Math.max(100, Math.min(5000, micros)) * 1000L;
		globalBudgetBytes = Math.max(1L << 20, Math.min(32L << 20, bytes));
	}
	public static int memberLimit() { return memberLimit; }

	/** Anchor chunk must be loaded. target is 1..16384; use DEFAULT_TARGET if omitted. */
	public static JsonObject start(ServerLevel level, BlockPos pos, int target) {
		expire();
		if (target < 1 || target > memberLimit) return error("bad_request", "target exceeds the configured member limit " + memberLimit);
		if (JOBS.size() >= MAX_JOBS) return error("capacity", "Too many retained jobs; cancel or wait for expiry.");
		if (!level.hasChunkAt(pos)) return error("unloaded", "Anchor chunk is not loaded.");
		BlockEntity anchor = level.getBlockEntity(pos);
		JsonObject summary = CreateAdapterProvider.network().summary(anchor);
		if (!"ok".equals(summary.get("status").getAsString())) return summary;
		try {
			CreateNetworkAdapter.NetworkView view = CreateNetworkAdapter.resolve(anchor);
			if (view == null) return error("unavailable", "Network cache unavailable.");
			String id = UUID.randomUUID().toString();
			Job job = new Job(id, Levels.dimensionId(level), String.valueOf(view.id), pos, target, level.getGameTime(), summary, view.members.size());
			JOBS.put(id, job);
			return describe(job);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return error("unavailable", "Could not begin network job: " + e.getClass().getSimpleName());
		}
	}

	/** Call once per server tick, on that server's thread. One running job gets one bounded slice. */
	public static void tick(MinecraftServer server) {
		expire();
		Job first = null, chosen = null;
		boolean afterLast = lastScheduled == null;
		for (Job job : JOBS.values()) {
			if (!"running".equals(job.state)) continue;
			if (first == null) first = job;
			if (afterLast) { chosen = job; break; }
			if (job.id.equals(lastScheduled)) afterLast = true;
		}
		if (chosen == null) chosen = first;
		if (chosen != null) { lastScheduled = chosen.id; advance(server, chosen); }
	}

	private static void advance(MinecraftServer server, Job job) {
		long start = System.nanoTime();
		try {
			ServerLevel level = Levels.resolve(server, job.dimension);
			job.endTick = level.getGameTime();
			if (!level.hasChunkAt(job.anchor)) { job.stop("partial", "anchor_chunk_unloaded"); return; }
			CreateNetworkAdapter.NetworkView view = CreateNetworkAdapter.resolve(level.getBlockEntity(job.anchor));
			if (view == null || !job.networkId.equals(String.valueOf(view.id))) { job.stop("partial", "anchor_network_changed_or_unavailable"); return; }
			if (view.members.size() != job.initialMemberCount) job.changeDetected = true;
			if (!job.cursor.attach(view.members)) { job.stop("partial", "network_changed_too_often"); return; }
			int processed = 0;
			while (processed < nodesPerTick && (processed == 0 || System.nanoTime() - start < tickNanos) && job.cursor.hasNext()) {
				Map.Entry<?, ?> entry = job.cursor.next(); processed++; job.visited++;
				JsonObject item = CreateNetworkAdapter.member(view, entry);
				if (item == null) { job.changeDetected = true; continue; }
				Key key = new Key(item.get("x").getAsInt(), item.get("y").getAsInt(), item.get("z").getAsInt());
				if (job.records.containsKey(key)) continue;
				item.addProperty("sampleGameTime", job.endTick);
				String json = item.toString();
				if (json.length() > MAX_RECORD_CHARS) { job.stop("partial", "record_size_limit"); return; }
				long estimate = 2L * json.length() + 256;
				if (usedRecords >= MAX_RECORDS || usedBytes + estimate > globalBudgetBytes) { job.stop("partial", "global_memory_limit"); return; }
				job.records.put(key, json); job.bytes += estimate; usedRecords++; usedBytes += estimate;
				if (job.records.size() >= job.target) {
					boolean more = job.cursor.hasNext();
					job.stop(more ? "partial" : "finished", more ? "target_limit" : "iteration_end"); return;
				}
			}
			if (!job.cursor.hasNext()) job.stop("finished", "iteration_end");
		} catch (ConcurrentModificationException e) {
			restart(job);
		} catch (Exception | LinkageError e) {
			job.stop("partial", "sampling_error_" + e.getClass().getSimpleName());
		}
	}

	private static void restart(Job job) {
		job.changeDetected = true;
		if (!job.cursor.restart()) job.stop("partial", "network_changed_too_often");
	}

	public static JsonObject status(String id) {
		expire(); Job job = JOBS.get(id);
		return job == null ? error("not_found", "Job expired or does not exist.") : describe(job);
	}

	/** Stable pages are available only after the job reaches a terminal state. */
	public static JsonObject page(String id, int offset, int limit) {
		expire(); Job job = JOBS.get(id);
		if (job == null) return error("not_found", "Job expired or does not exist.");
		if (offset < 0 || offset > job.records.size() || limit < 1 || limit > 64) return error("bad_request", "Invalid page bounds.");
		if ("running".equals(job.state)) return error("not_ready", "Wait until the job reaches a terminal state.");
		JsonObject out = describe(job); JsonArray page = new JsonArray();
		int index = 0;
		for (String record : job.records.values()) {
			if (index++ < offset) continue;
			if (page.size() == limit) break;
			page.add(JsonParser.parseString(record));
		}
		int next = offset + page.size();
		out.add("members", page); out.addProperty("offset", offset); out.addProperty("hasMore", next < job.records.size());
		if (next < job.records.size()) out.addProperty("nextOffset", next);
		return out;
	}

	public static JsonObject cancel(String id) {
		expire(); Job job = JOBS.remove(id);
		if (job == null) return error("not_found", "Job expired or does not exist.");
		job.stop("cancelled", "cancelled"); release(job);
		return error("cancelled", "Job and cached members removed.");
	}

	/** Invoke on server stop or replacement to release iterators, game references, and captured data. */
	public static void clear() { for (Job job : JOBS.values()) { job.stop("cancelled", "server_stopped"); release(job); } JOBS.clear(); lastScheduled = null; }

	private static void expire() {
		long now = System.nanoTime(); Iterator<Job> it = JOBS.values().iterator();
		while (it.hasNext()) { Job job = it.next(); if (now - job.createdNanos >= TTL_NANOS) { job.stop("expired", "ttl"); release(job); it.remove(); } }
	}
	private static void release(Job job) { usedBytes -= job.bytes; usedRecords -= job.records.size(); job.records.clear(); job.bytes = 0; }
	private static JsonObject describe(Job j) {
		JsonObject o = new JsonObject(); o.addProperty("status", j.state); o.addProperty("reason", j.reason); o.addProperty("jobId", j.id);
		o.addProperty("dimension", j.dimension); o.addProperty("networkId", j.networkId); o.addProperty("startTick", j.startTick); o.addProperty("endTick", j.endTick);
		o.addProperty("target", j.target); o.addProperty("captured", j.records.size()); o.addProperty("visited", j.visited); o.addProperty("restarts", j.cursor.restarts());
		o.addProperty("changeDetected", j.changeDetected || j.cursor.changed()); o.addProperty("ttlSecondsFromStart", 120);
		o.addProperty("consistency", "best_effort_non_atomic: members sampled across ticks; topology can change undetected, so completeness is not guaranteed");
		o.addProperty("nodesPerTick", nodesPerTick); o.addProperty("tickBudgetMicros", tickNanos / 1000);
		o.addProperty("budgetNote", "soft limit globally, with at least one attempted member per active tick; individual getters may exceed it; memory counts conservative per-record estimates");
		o.add("initialSummary", j.initialSummary.deepCopy()); return o;
	}
	private static JsonObject error(String code, String message) { JsonObject o = new JsonObject(); o.addProperty("status", code); o.addProperty("message", message); return o; }
}
