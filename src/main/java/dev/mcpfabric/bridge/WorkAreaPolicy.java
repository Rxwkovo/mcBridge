package dev.mcpfabric.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.construction.ConstructionPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** One server-owned policy for all region operations. Cached parsing never caches a grant. */
public final class WorkAreaPolicy {
	private static final int MAX_BYTES = 16_384;
	private static final int MAX_EDITS = 128;
	private static final long MAX_VOLUME = 65_536;
	private static final java.util.regex.Pattern RESOURCE_ID = java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
	private static final WorkAreaPolicy SHARED = new WorkAreaPolicy();

	public record Area(String id, String dimension, ConstructionPolicy.Bounds bounds, int maxBatch,
			String fingerprint, Set<String> allowedBlocks) {}
	private record Document(byte[] contents, Path world, Map<String, Area> areas) {}
	private Document parsed;

	public static Area require(MinecraftServer server, String id, String dimension) throws RpcException {
		return SHARED.require(McpFabric.platform().configDir().resolve("mechagent-construction.json"),
				server.getWorldPath(LevelResource.ROOT), id, dimension);
	}

	public static boolean contains(MinecraftServer server, String id, String dimension,
			int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		try {
			var bounds = require(server, id, dimension).bounds();
			return bounds.contains(minX, minY, minZ) && bounds.contains(maxX, maxY, maxZ);
		} catch (RpcException e) { return false; }
	}

	synchronized Area require(Path file, Path world, String id, String dimension) throws RpcException {
		try {
			if (!Files.isRegularFile(file)) throw new IllegalArgumentException();
			byte[] bytes;
			try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
			if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
			if (parsed == null || !Arrays.equals(bytes, parsed.contents())) parsed = parse(bytes);
			if (!parsed.world().toRealPath().equals(world.toRealPath())) throw new IllegalArgumentException();
			Area area = parsed.areas().get(id);
			if (area == null || !area.dimension().equals(dimension)) throw new IllegalArgumentException();
			return area;
		} catch (Exception e) {
			throw RpcException.unavailable("No enabled, valid server-owned work area for this world and dimension.");
		}
	}

	private static Document parse(byte[] bytes) throws Exception {
		var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		if (!root.has("enabled") || !root.get("enabled").getAsBoolean()) throw new IllegalArgumentException();
		var limits = new RpcContext("limits", root);
		int maxBatch = limits.optInt("maxBatch", MAX_EDITS);
		long maxVolume = limits.optLong("maxVolume", MAX_VOLUME);
		if (maxBatch < 1 || maxBatch > MAX_EDITS || maxVolume < 1 || maxVolume > MAX_VOLUME)
			throw new IllegalArgumentException();
		Path world = Path.of(root.get("worldPath").getAsString());
		var entries = root.getAsJsonArray("areas");
		if (!world.isAbsolute() || entries.size() > 32) throw new IllegalArgumentException();
		String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		var areas = new HashMap<String, Area>();
		for (JsonElement entry : entries) {
			var a = entry.getAsJsonObject();
			String id = a.get("id").getAsString(), dimension = a.get("dimension").getAsString();
			if (!ConstructionPolicy.validKey(id) || !RESOURCE_ID.matcher(dimension).matches())
				throw new IllegalArgumentException();
			var lo = new RpcContext("min", a.getAsJsonObject("min"));
			var hi = new RpcContext("max", a.getAsJsonObject("max"));
			var bounds = new ConstructionPolicy.Bounds(lo.getInt("x"), lo.getInt("y"), lo.getInt("z"),
					hi.getInt("x"), hi.getInt("y"), hi.getInt("z"));
			long dx = (long) bounds.maxX() - bounds.minX() + 1;
			long dy = (long) bounds.maxY() - bounds.minY() + 1;
			long dz = (long) bounds.maxZ() - bounds.minZ() + 1;
			if (dx > maxVolume || dy > maxVolume / dx || dz > maxVolume / (dx * dy)) throw new IllegalArgumentException();
			Set<String> allowed = new HashSet<>();
			if (a.has("allowedBlocks")) {
				var blocks = a.getAsJsonArray("allowedBlocks");
				if (blocks.size() > 128) throw new IllegalArgumentException();
				for (JsonElement item : blocks) {
					if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
					String name = item.getAsString();
					if (!RESOURCE_ID.matcher(name).matches() || !allowed.add(name)) throw new IllegalArgumentException();
				}
			}
			if (areas.putIfAbsent(id, new Area(id, dimension, bounds, maxBatch, fingerprint, Set.copyOf(allowed))) != null)
				throw new IllegalArgumentException();
		}
		return new Document(bytes, world, Map.copyOf(areas));
	}
}
