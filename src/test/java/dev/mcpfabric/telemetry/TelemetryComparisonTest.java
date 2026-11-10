package dev.mcpfabric.telemetry;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryComparisonTest {
	@Test void comparesOnlyValuesActuallyObservedInBothPeriods(){
		JsonObject first=JsonParser.parseString("{\"points\":[{\"position\":{\"x\":1,\"y\":2,\"z\":3},\"averageRpm\":-8,\"averageStressSU\":64}]}").getAsJsonObject();
		JsonObject second=JsonParser.parseString("{\"points\":[{\"position\":{\"x\":1,\"y\":2,\"z\":3},\"averageRpm\":-16,\"averageUtilization\":0.5}]}").getAsJsonObject();
		JsonObject delta=TelemetryComparison.compare(first,second).getAsJsonArray("points").get(0).getAsJsonObject().getAsJsonObject("deltaSecondMinusFirst");
		assertEquals(-8,delta.get("averageRpm").getAsDouble());assertFalse(delta.has("averageStressSU"));assertFalse(delta.has("averageUtilization"));
	}
}
