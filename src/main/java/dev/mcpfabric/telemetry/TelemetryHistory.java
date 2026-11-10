package dev.mcpfabric.telemetry;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded disk history scan; invoked only by a background worker. */
final class TelemetryHistory {
	private TelemetryHistory() {}
	static JsonObject read(Path data, String id, String area, String dimension, TelemetryPolicy.Box box,
			long since, int limit, Instant from, Instant to, String eventType) throws Exception {
		List<Path> files = new ArrayList<>(); Path legacy = data.resolve("mechagent-telemetry.jsonl");
		if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) files.add(legacy);
		try (var listing = Files.list(data)) {
			files.addAll(listing.filter(p -> p.getFileName().toString().matches("mechagent-telemetry-[0-9]{8}\\.jsonl")).sorted().toList());
		}
		if (files.size() > 4096) throw new IllegalStateException("too many segments");
		JsonArray result = new JsonArray(); boolean scopeMatches = false, more = false, incomplete = false;
		long next = since, totalBytes = 0;
		for (int index = 0; index < files.size(); index++) {
			Path path = files.get(index);
			if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("invalid segment");
			long length = Files.size(path); totalBytes += length;
			if (length > TelemetryJournal.MAX_BYTES || totalBytes > 512L * 1024 * 1024) throw new IllegalStateException("history scan budget exceeded");
			byte[] raw = Files.readAllBytes(path); int lengthToRead = raw.length;
			if (lengthToRead > 0 && raw[lengthToRead - 1] != '\n') {
				if (index != files.size() - 1) throw new IllegalStateException("incomplete closed segment");
				while (lengthToRead > 0 && raw[lengthToRead - 1] != '\n') lengthToRead--;
				incomplete = true;
			}
			for (String line : new String(raw, 0, lengthToRead, StandardCharsets.UTF_8).split("\\n")) {
				if (line.isEmpty()) continue;
				if (line.length() > 8192) throw new IllegalStateException("invalid record");
				JsonObject record = JsonParser.parseString(line).getAsJsonObject();
				if (!record.has("monitorId") || !id.equals(record.get("monitorId").getAsString())) continue;
				String type = record.get("type").getAsString();
				if (type.equals("define")) {
					scopeMatches = matches(record, area, dimension, box);
					result = new JsonArray(); more = false; next = since;
					continue;
				}
				if (!scopeMatches || (!type.equals("sample") && !type.equals("gap"))) continue;
				long sequence = record.get("sequence").getAsLong(); if (sequence <= since) continue;
				if (eventType != null && !eventType.equals(type)) continue;
				Instant time = Instant.parse(record.get("wallTimeUtc").getAsString());
				if ((from != null && time.isBefore(from)) || (to != null && time.isAfter(to))) continue;
				if (result.size() >= limit) { more = true; continue; }
				result.add(record); next = sequence;
			}
		}
		JsonObject out = new JsonObject(); out.addProperty("status", "ok"); out.add("entries", result);
		out.addProperty("hasMore", more); out.addProperty("nextSequence", next);
		out.addProperty("consistency", "non_atomic_disk_scan"); out.addProperty("incompleteActiveTailIgnored", incomplete);
		out.addProperty("scanCompletedUtc", Instant.now().toString()); out.addProperty("cacheTtlSeconds", 60);
		out.addProperty("scope", "latest_monitor_definition_only"); return out;
	}
	static boolean matches(JsonObject define, String area, String dimension, TelemetryPolicy.Box box) {
		try {
			JsonObject a = define.getAsJsonObject("min"), b = define.getAsJsonObject("max");
			return area.equals(define.get("area").getAsString()) && dimension.equals(define.get("dimension").getAsString())
					&& a.get("x").getAsInt() == box.minX() && a.get("y").getAsInt() == box.minY() && a.get("z").getAsInt() == box.minZ()
					&& b.get("x").getAsInt() == box.maxX() && b.get("y").getAsInt() == box.maxY() && b.get("z").getAsInt() == box.maxZ();
		} catch (RuntimeException e) { return false; }
	}
}
