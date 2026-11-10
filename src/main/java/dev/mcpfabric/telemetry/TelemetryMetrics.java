package dev.mcpfabric.telemetry;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import java.util.Map;
import java.util.TreeSet;

/** Inventory observations cannot distinguish production from transfers or consumption. */
public final class TelemetryMetrics {
	private TelemetryMetrics() {}
	/** Derive a ratio only from valid Create-provided cached SU values. */
	public static JsonObject kineticWithCachedUtilization(JsonObject observed) {
		JsonObject result = observed.deepCopy(); result.add("networkUtilization", JsonNull.INSTANCE);
		result.addProperty("networkUtilizationStatus", "unavailable_missing_or_zero_capacity");
		try {
			if (!observed.has("networkStressCapacity") || !observed.has("networkStressConsumption")) return result;
			double capacity = observed.get("networkStressCapacity").getAsDouble();
			double stress = observed.get("networkStressConsumption").getAsDouble();
			if (!Double.isFinite(capacity) || !Double.isFinite(stress) || capacity <= 0 || stress < 0) return result;
			double ratio = stress / capacity; if (!Double.isFinite(ratio)) return result;
			result.addProperty("networkUtilization", ratio);
			result.addProperty("networkUtilizationStatus", "derived_from_create_cached_su_may_be_stale");
		} catch (RuntimeException ignored) { }
		return result;
	}
	public static JsonObject inventoryNet(Map<String, Long> before, Map<String, Long> after, boolean complete) {
		JsonObject o = new JsonObject();
		o.addProperty("throughputStatus", "unsupported_no_transfer_counter");
		o.addProperty("productionStatus", "unsupported_no_production_counter");
		o.addProperty("meaning", "Inventory net change only; transfers, production and consumption cannot be distinguished.");
		if (!complete || before == null || after == null) { o.addProperty("netChangeStatus", "unavailable_gap_or_incomplete"); return o; }
		JsonObject net = new JsonObject();
		TreeSet<String> ids = new TreeSet<>(before.keySet()); ids.addAll(after.keySet());
		for (String id : ids) { long delta = after.getOrDefault(id, 0L) - before.getOrDefault(id, 0L); if (delta != 0) net.addProperty(id, delta); }
		o.addProperty("netChangeStatus", "available"); o.add("inventoryNetChange", net);
		return o;
	}
}
