package dev.mcpfabric.adapters;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateKineticAdapterTest {
	public static class FakeNetworkSnapshot {
		protected float capacity = 256;
		protected float stress = 120;
		private int networkSize = 3;
		public boolean networkDirty = true;
	}

	public static class MissingNetworkFields {}

	@Test
	void returnsCachedFieldsWithoutRecalculating() {
		FakeNetworkSnapshot sample = new FakeNetworkSnapshot();
		JsonObject result = new JsonObject();
		CreateKineticAdapter.readNetworkSnapshot(FakeNetworkSnapshot.class, sample, result);
		assertEquals("cached_snapshot", result.get("networkStatus").getAsString());
		assertEquals(256f, result.get("networkStressCapacity").getAsFloat());
		assertEquals(120f, result.get("networkStressConsumption").getAsFloat());
		assertEquals(3, result.get("networkSize").getAsInt());
		assertTrue(result.get("networkDirty").getAsBoolean());
		assertEquals(120f, sample.stress);
	}

	@Test
	void missingFieldsAreUnavailableRatherThanZero() {
		JsonObject result = new JsonObject();
		CreateKineticAdapter.readNetworkSnapshot(MissingNetworkFields.class, new MissingNetworkFields(), result);
		assertEquals("unavailable", result.get("networkStatus").getAsString());
		assertFalse(result.has("networkStressCapacity"));
	}

	@Test
	void invalidNetworkValueDoesNotBecomeAValidMeasurement() {
		FakeNetworkSnapshot sample = new FakeNetworkSnapshot();
		sample.stress = Float.NaN;
		JsonObject result = new JsonObject();
		CreateKineticAdapter.readNetworkSnapshot(FakeNetworkSnapshot.class, sample, result);
		assertEquals("invalid_data", result.get("networkStatus").getAsString());
		assertFalse(result.has("networkStressConsumption"));
	}
}
