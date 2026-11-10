package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryHistoryTest {
	private static final TelemetryPolicy.Box BOX = new TelemetryPolicy.Box(1, 2, 3, 1, 2, 3);
	private static String define(String area, int sequence) {
		return "{\"type\":\"define\",\"monitorId\":\"m\",\"sequence\":" + sequence + ",\"area\":\"" + area + "\",\"dimension\":\"minecraft:overworld\",\"min\":{\"x\":1,\"y\":2,\"z\":3},\"max\":{\"x\":1,\"y\":2,\"z\":3}}\n";
	}
	private static String sample(int seq, String type, String time) {
		return "{\"type\":\"" + type + "\",\"monitorId\":\"m\",\"sequence\":" + seq + ",\"wallTimeUtc\":\"" + time + "\"}\n";
	}
	@Test void pagesAndFiltersAcrossSegments(@TempDir Path data) throws Exception {
		Files.writeString(data.resolve("mechagent-telemetry.jsonl"), define("area", 1) + sample(2,"sample","2026-01-01T00:00:01Z"));
		Files.writeString(data.resolve("mechagent-telemetry-00000001.jsonl"), sample(3,"sample","2026-01-01T00:00:02Z") + sample(4,"gap","2026-01-01T00:00:03Z"));
		JsonObject first = TelemetryHistory.read(data,"m","area","minecraft:overworld",BOX,0,1,null,null,"sample");
		assertEquals(2, first.getAsJsonArray("entries").get(0).getAsJsonObject().get("sequence").getAsInt());
		assertTrue(first.get("hasMore").getAsBoolean());
		JsonObject next = TelemetryHistory.read(data,"m","area","minecraft:overworld",BOX,2,10,Instant.parse("2026-01-01T00:00:02Z"),null,"sample");
		assertEquals(1, next.getAsJsonArray("entries").size()); assertEquals(3, next.get("nextSequence").getAsInt());
	}
	@Test void priorScopeCannotLeakThroughReconfiguredMonitor(@TempDir Path data) throws Exception {
		Files.writeString(data.resolve("mechagent-telemetry.jsonl"), define("old",1) + sample(2,"sample","2026-01-01T00:00:01Z") + define("new",3) + sample(4,"sample","2026-01-01T00:00:02Z"));
		JsonObject output = TelemetryHistory.read(data,"m","new","minecraft:overworld",BOX,0,100,null,null,null);
		assertEquals(1, output.getAsJsonArray("entries").size());
		assertEquals(4, output.getAsJsonArray("entries").get(0).getAsJsonObject().get("sequence").getAsInt());
	}
}
