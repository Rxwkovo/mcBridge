package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;

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

	@Test
	void snapshotPagesAreSortedAndUnaffectedByLaterMutations() {
		JsonObject metadata = new JsonObject();
		metadata.addProperty("status", "ok");
		JsonObject b = new JsonObject(); b.addProperty("x", 8); b.addProperty("y", 2); b.addProperty("z", 1);
		JsonObject a = new JsonObject(); a.addProperty("x", -1); a.addProperty("y", 3); a.addProperty("z", 0);
		String id = CreateNetworkAdapter.storeSnapshot(metadata, List.of(b, a));
		a.addProperty("x", 99);
		JsonObject first = CreateAdapterProvider.network().page(id, 0, 1);
		JsonObject second = CreateAdapterProvider.network().page(id, first.get("nextOffset").getAsInt(), 1);
		assertEquals(-1, first.getAsJsonArray("members").get(0).getAsJsonObject().get("x").getAsInt());
		assertEquals(8, second.getAsJsonArray("members").get(0).getAsJsonObject().get("x").getAsInt());
		assertFalse(second.get("hasMore").getAsBoolean());
		assertEquals("snapshot_expired_or_unknown", CreateAdapterProvider.network().page("unknown", 0, 1).get("status").getAsString());
	}
}
