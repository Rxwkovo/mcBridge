package dev.mcpfabric.telemetry;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryWindowTest {
	private static final TelemetryPolicy.Box BOX=new TelemetryPolicy.Box(1,2,3,1,2,3);
	private static JsonObject definition(){return JsonParser.parseString("{\"type\":\"define\",\"monitorId\":\"m\",\"area\":\"a\",\"dimension\":\"minecraft:overworld\",\"min\":{\"x\":1,\"y\":2,\"z\":3},\"max\":{\"x\":1,\"y\":2,\"z\":3}}").getAsJsonObject();}
	private static JsonObject sample(int seconds,int tick,int rpm,int inventory){
		JsonObject r=new JsonObject();r.addProperty("type","sample");r.addProperty("monitorId","m");r.addProperty("gameTime",tick);r.addProperty("wallTimeUtc",Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds).toString());
		JsonObject pos=new JsonObject();pos.addProperty("x",1);pos.addProperty("y",2);pos.addProperty("z",3);r.add("position",pos);
		JsonObject kinetic=new JsonObject();kinetic.addProperty("status","ok");kinetic.addProperty("rpm",rpm);kinetic.addProperty("overstressed",false);kinetic.addProperty("networkStressCapacity",100);kinetic.addProperty("networkStressConsumption",50);r.add("kinetic",kinetic);
		JsonObject item=new JsonObject();item.addProperty("id","minecraft:iron_ingot");item.addProperty("count",inventory);JsonArray items=new JsonArray();if(inventory>0)items.add(item);
		JsonObject inv=new JsonObject();inv.addProperty("status","available");inv.add("contents",items);JsonObject caps=new JsonObject();caps.add("items",inv);r.add("capabilities",caps);return r;
	}
	private static TelemetryWindow window(boolean ticks){return new TelemetryWindow("m","a","minecraft:overworld",BOX,Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-01T00:00:10Z"),0,200,ticks,20);}
	@Test void usesActualIntervalsAndReportsInventoryNetWithoutProduction(){
		TelemetryWindow w=window(false);w.accept(definition());w.accept(sample(0,0,10,2));w.accept(sample(1,20,20,1));w.accept(sample(3,60,30,3));
		JsonObject result=w.finish(),p=result.getAsJsonArray("points").get(0).getAsJsonObject();
		assertEquals(50.0/3,p.get("averageRpm").getAsDouble(),0.00001);assertEquals(3,p.get("observedWeight").getAsDouble());
		assertEquals(0.3,p.get("kineticCoverageFraction").getAsDouble(),0.00001);assertEquals(1,p.getAsJsonObject("inventoryNetChange").get("minecraft:iron_ingot").getAsLong());
		assertEquals("unsupported_no_production_counter",result.get("productionStatus").getAsString());
	}
	@Test void gapsNeverBecomeContinuousTimeOrInventInventoryPairs(){
		TelemetryWindow w=window(true);w.accept(definition());w.accept(sample(0,0,10,2));
		JsonObject gap=new JsonObject();gap.addProperty("type","gap");gap.addProperty("monitorId","m");gap.addProperty("wallTimeUtc","2026-01-01T00:00:01Z");gap.addProperty("gameTime",0);w.accept(gap);w.accept(sample(2,40,0,3));
		JsonObject p=w.finish().getAsJsonArray("points").get(0).getAsJsonObject();assertFalse(p.has("averageRpm"));
		assertEquals("insufficient_samples",p.get("inventoryNetStatus").getAsString());assertFalse(p.has("inventoryNetChange"));
	}
}
