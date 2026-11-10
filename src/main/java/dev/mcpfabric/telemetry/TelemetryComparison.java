package dev.mcpfabric.telemetry;

import com.google.gson.*;
import java.util.*;

/** Compares observed values only; absence is never replaced by zero. */
final class TelemetryComparison {
	private TelemetryComparison() {}
	static JsonObject compare(JsonObject first, JsonObject second) {
		JsonObject out=new JsonObject();out.addProperty("status","ok");out.add("first",first);out.add("second",second);
		out.addProperty("interpretation","Differences compare observed sample-weighted values; coverage, gaps, caching and different windows limit causal conclusions.");
		Map<String,JsonObject> before=new HashMap<>();for(JsonElement e:first.getAsJsonArray("points")){JsonObject p=e.getAsJsonObject();before.put(p.get("position").toString(),p);}
		JsonArray changes=new JsonArray();for(JsonElement e:second.getAsJsonArray("points")){
			JsonObject after=e.getAsJsonObject(), prev=before.get(after.get("position").toString());if(prev==null)continue;
			JsonObject point=new JsonObject();point.add("position",after.get("position").deepCopy());JsonObject delta=new JsonObject();
			for(String key:List.of("averageRpm","minRpm","maxRpm","averageStressSU","peakStressSU","averageCapacitySU","peakCapacitySU","averageUtilization","peakUtilization","kineticCoverageFraction")){
				if(prev.has(key)&&after.has(key)&&!prev.get(key).isJsonNull()&&!after.get(key).isJsonNull()){
					double d=after.get(key).getAsDouble()-prev.get(key).getAsDouble();if(Double.isFinite(d))delta.addProperty(key,d);
				}
			}
			point.add("deltaSecondMinusFirst",delta);changes.add(point);
		}out.add("points",changes);return out;
	}
}
