package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreateNetworkAdapterTest {
	static class CachedNetwork {
		private float currentCapacity = 1000;
		private float currentStress = 600;
		private float unloadedCapacity = 200;
		private float unloadedStress = 50;
		private int unloadedMembers = 2;
	}

	static class MissingFields {}

	@Test
	void readsCachedTotalsAndIdentifiesUnloadedContributions() {
		JsonObject result = new JsonObject();
		CreateNetworkAdapter.readTotals(CachedNetwork.class, new CachedNetwork(), result);
		assertEquals("cached_may_be_stale", result.get("totalsStatus").getAsString());
		assertEquals(1000, result.get("capacitySU").getAsFloat());
		assertEquals(600, result.get("stressSU").getAsFloat());
		assertEquals(400, result.get("remainingSU").getAsFloat());
		assertEquals(0.6, result.get("utilization").getAsDouble(), 1e-9);
		assertEquals(200, result.get("unloadedCapacitySU").getAsFloat());
		assertEquals(50, result.get("unloadedStressSU").getAsFloat());
		assertEquals(2, result.get("unloadedMembers").getAsInt());
	}

	@Test
	void missingAndNonfiniteTotalsAreNotReportedAsZero() {
		JsonObject missing = new JsonObject();
		CreateNetworkAdapter.readTotals(MissingFields.class, new MissingFields(), missing);
		assertEquals("unavailable", missing.get("totalsStatus").getAsString());
		assertFalse(missing.has("capacitySU"));
		CachedNetwork bad = new CachedNetwork();
		bad.currentStress = Float.NaN;
		JsonObject invalid = new JsonObject();
		CreateNetworkAdapter.readTotals(CachedNetwork.class, bad, invalid);
		assertEquals("invalid_data", invalid.get("totalsStatus").getAsString());
		assertFalse(invalid.has("stressSU"));
	}

	@Test
	void refusesUnboundedPagesBeforeWorldAccess() {
		assertFalse(CreateNetworkAdapter.validPage(-1, 1));
		assertFalse(CreateNetworkAdapter.validPage(4096, 1));
		assertFalse(CreateNetworkAdapter.validPage(0, 65));
		assertTrue(CreateNetworkAdapter.validPage(4095, 64));
	}
}
