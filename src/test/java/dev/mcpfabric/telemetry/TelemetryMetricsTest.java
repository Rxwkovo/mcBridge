package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryMetricsTest {
	@Test void inventoryDifferenceNeverClaimsThroughputOrProduction() {
		JsonObject m = TelemetryMetrics.inventoryNet(Map.of("minecraft:iron_ingot", 10L), Map.of("minecraft:iron_ingot", 7L), true);
		assertEquals(-3, m.getAsJsonObject("inventoryNetChange").get("minecraft:iron_ingot").getAsLong());
		assertEquals("unsupported_no_transfer_counter", m.get("throughputStatus").getAsString());
		assertEquals("unsupported_no_production_counter", m.get("productionStatus").getAsString());
		assertTrue(m.get("meaning").getAsString().contains("cannot be distinguished"));
	}
	@Test void gapNeverBecomesZeroDelta() {
		JsonObject m = TelemetryMetrics.inventoryNet(Map.of("a", 1L), Map.of("a", 1L), false);
		assertEquals("unavailable_gap_or_incomplete", m.get("netChangeStatus").getAsString());
		assertFalse(m.has("inventoryNetChange"));
	}
	@Test void equalInventoryDoesNotClaimNoProductionOrTransfer() {
		JsonObject m = TelemetryMetrics.inventoryNet(Map.of("minecraft:iron_ingot", 10L), Map.of("minecraft:iron_ingot", 10L), true);
		assertEquals("available", m.get("netChangeStatus").getAsString());
		assertTrue(m.getAsJsonObject("inventoryNetChange").isEmpty());
		assertEquals("unsupported_no_transfer_counter", m.get("throughputStatus").getAsString());
		assertEquals("unsupported_no_production_counter", m.get("productionStatus").getAsString());
	}
	@Test void stressUtilizationUsesValidCachedValuesWithoutInventingZeros() {
		JsonObject observation = new JsonObject(); observation.addProperty("networkStressCapacity", 256);
		observation.addProperty("networkStressConsumption", 128);
		JsonObject result = TelemetryMetrics.kineticWithCachedUtilization(observation);
		assertEquals(0.5, result.get("networkUtilization").getAsDouble());
		assertTrue(result.get("networkUtilizationStatus").getAsString().contains("cached"));
		observation.addProperty("networkStressCapacity", 0);
		assertTrue(TelemetryMetrics.kineticWithCachedUtilization(observation).get("networkUtilization").isJsonNull());
		assertTrue(TelemetryMetrics.kineticWithCachedUtilization(new JsonObject()).get("networkUtilization").isJsonNull());
	}
}
