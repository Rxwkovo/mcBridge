package dev.mcpfabric.telemetry;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded, non-atomic history scan. Averages are time-weighted over observed adjacent samples. */
final class TelemetryWindow {
	private static final int MAX_POINTS = 64;
	private final String id, area, dimension; private final TelemetryPolicy.Box box;
	private final Instant from, to; private final long fromTick, toTick, maxTickGap;
	private final boolean tickBasis;
	private final Map<String, Point> points = new LinkedHashMap<>();
	private boolean scope, incompleteTail, tickWindowEnded; private int gaps, samples, rejectedIntervals;
	private static final class Point {
		JsonObject pos; long lastTick; Instant lastTime; boolean hasPrevious, priorKinetic;
		double priorRpm, priorStress, priorCapacity, priorUtil; boolean priorStressValid, priorCapacityValid, priorUtilValid, priorOverstressed, priorOverstressedKnown;
		long kineticSamples; double weight, rpmWeighted, minRpm=Double.POSITIVE_INFINITY, maxRpm=Double.NEGATIVE_INFINITY;
		double stressWeight, stressWeighted, maxStress=Double.NEGATIVE_INFINITY, capacityWeight, capacityWeighted, maxCapacity=Double.NEGATIVE_INFINITY;
		double utilWeight, utilWeighted, maxUtil=Double.NEGATIVE_INFINITY, running, stopped, overloaded, overloadWeight;
		Map<String, Long> inventory; final Map<String, Long> net = new TreeMap<>(); boolean inventorySeen, inventoryGap; int inventoryPairs;
	}
	TelemetryWindow(String id, String area, String dimension, TelemetryPolicy.Box box, Instant from, Instant to,
			long fromTick, long toTick, boolean tickBasis, int interval) {
		this.id=id; this.area=area; this.dimension=dimension; this.box=box; this.from=from; this.to=to;
		this.fromTick=fromTick; this.toTick=toTick; this.tickBasis=tickBasis;
		this.maxTickGap=Math.max(60L, ((long) interval + box.volume() + 1) * 3L);
	}
	void accept(JsonObject r) {
		if (!r.has("monitorId") || !id.equals(r.get("monitorId").getAsString())) return;
		String type = r.get("type").getAsString();
		if (type.equals("define")) { scope=TelemetryHistory.matches(r,area,dimension,box); points.clear(); gaps=0; samples=0; rejectedIntervals=0; tickWindowEnded=false; return; }
		if (!scope) return;
		Instant time; try { time=Instant.parse(r.get("wallTimeUtc").getAsString()); } catch (RuntimeException e) { gaps++; reset(); return; }
		long tick=r.has("gameTime") ? r.get("gameTime").getAsLong() : -1;
		if (type.equals("gap")) {
			if ((!tickBasis && time.isAfter(to)) || (tickBasis && tickWindowEnded)) return;
			if (inRange(time,tick) || (tickBasis && tick == 0 && samples > 0)) gaps++;
			reset(); return;
		}
		if (tickBasis && type.equals("sample") && tick > toTick) { tickWindowEnded=true; return; }
		if (!type.equals("sample") || !inRange(time,tick) || !r.has("position")) return;
		JsonObject pos=r.getAsJsonObject("position"); int x=pos.get("x").getAsInt(), y=pos.get("y").getAsInt(), z=pos.get("z").getAsInt();
		if (!box.contains(new TelemetryPolicy.Box(x,y,z,x,y,z))) { gaps++; return; }
		String key=x+","+y+","+z;
		if (!points.containsKey(key) && points.size() >= MAX_POINTS) { gaps++; return; }
		Point p=points.computeIfAbsent(key,k -> new Point()); p.pos=pos.deepCopy(); samples++;
		if (p.hasPrevious) {
			long dtTick=tick-p.lastTick; double dtWall=(time.toEpochMilli()-p.lastTime.toEpochMilli())/1000.0;
			if (dtTick <= 0 || dtTick > maxTickGap || dtWall <= 0 || dtWall > 30) { rejectedIntervals++; p.inventory=null; p.inventoryGap=true; }
			else if (p.priorKinetic) {
				double w=tickBasis ? dtTick : dtWall; p.weight+=w; p.rpmWeighted+=p.priorRpm*w;
				if (p.priorRpm == 0) p.stopped+=w; else p.running+=w;
				if (p.priorOverstressedKnown) { p.overloadWeight+=w; if (p.priorOverstressed) p.overloaded+=w; }
				if (p.priorStressValid) {p.stressWeight+=w;p.stressWeighted+=p.priorStress*w;}
				if (p.priorCapacityValid) {p.capacityWeight+=w;p.capacityWeighted+=p.priorCapacity*w;}
				if (p.priorUtilValid) {p.utilWeight+=w;p.utilWeighted+=p.priorUtil*w;}
			}
		}
		p.priorKinetic=false; JsonObject k=r.has("kinetic") && r.get("kinetic").isJsonObject()?r.getAsJsonObject("kinetic"):null;
		if (k != null && k.has("status") && "ok".equals(k.get("status").getAsString()) && finite(k,"rpm")) {
			p.priorKinetic=true; p.priorRpm=k.get("rpm").getAsDouble(); p.kineticSamples++;
			p.minRpm=Math.min(p.minRpm,p.priorRpm);p.maxRpm=Math.max(p.maxRpm,p.priorRpm);
			p.priorOverstressedKnown=k.has("overstressed") && !k.get("overstressed").isJsonNull() && k.get("overstressed").isJsonPrimitive() && k.get("overstressed").getAsJsonPrimitive().isBoolean();
			p.priorOverstressed=p.priorOverstressedKnown && k.get("overstressed").getAsBoolean();
			p.priorStressValid=finite(k,"networkStressConsumption") && k.get("networkStressConsumption").getAsDouble()>=0;
			p.priorCapacityValid=finite(k,"networkStressCapacity") && k.get("networkStressCapacity").getAsDouble()>=0;
			if(p.priorStressValid){p.priorStress=k.get("networkStressConsumption").getAsDouble();p.maxStress=Math.max(p.maxStress,p.priorStress);}
			if(p.priorCapacityValid){p.priorCapacity=k.get("networkStressCapacity").getAsDouble();p.maxCapacity=Math.max(p.maxCapacity,p.priorCapacity);}
			p.priorUtilValid=p.priorStressValid&&p.priorCapacityValid&&p.priorCapacity>0&&Double.isFinite(p.priorStress/p.priorCapacity);
			if(p.priorUtilValid){p.priorUtil=p.priorStress/p.priorCapacity;p.maxUtil=Math.max(p.maxUtil,p.priorUtil);}
		}
		Map<String,Long> inventory=inventory(r);
		if(inventory==null){if(p.inventory!=null)p.inventoryGap=true;p.inventory=null;}
		else {p.inventorySeen=true;if(p.inventory!=null){p.inventoryPairs++;Set<String> ids=new HashSet<>(p.inventory.keySet());ids.addAll(inventory.keySet());
			for(String item:ids)p.net.merge(item,Math.subtractExact(inventory.getOrDefault(item,0L),p.inventory.getOrDefault(item,0L)),Math::addExact);}
			p.inventory=inventory;}
		p.hasPrevious=true;p.lastTick=tick;p.lastTime=time;
	}
	private boolean inRange(Instant time,long tick){return tickBasis ? tick>=fromTick&&tick<=toTick : !time.isBefore(from)&&!time.isAfter(to);}
	private void reset(){for(Point p:points.values()){p.hasPrevious=false;p.priorKinetic=false;p.inventory=null;p.inventoryGap=true;}}
	private static boolean finite(JsonObject o,String key){try{return o.has(key)&&!o.get(key).isJsonNull()&&Double.isFinite(o.get(key).getAsDouble());}catch(RuntimeException e){return false;}}
	private static Map<String,Long> inventory(JsonObject record){
		try{JsonObject caps=record.getAsJsonObject("capabilities"), items=caps.getAsJsonObject("items");
			if(!"available".equals(items.get("status").getAsString())||(items.has("truncated")&&items.get("truncated").getAsBoolean()))return null;
			JsonArray arr=items.getAsJsonArray("contents");if(arr==null||arr.size()>64)return null;Map<String,Long> out=new HashMap<>();
			for(JsonElement e:arr){JsonObject item=e.getAsJsonObject();String id=item.get("id").getAsString();long count=item.get("count").getAsLong();if(id.length()>256||count<0)return null;out.merge(id,count,Math::addExact);}return out;
		}catch(RuntimeException e){return null;}
	}
	JsonObject finish(){
		JsonObject out=new JsonObject();out.addProperty("status","ok");out.addProperty("timeBasis",tickBasis?"game_ticks":"wall_seconds");
		if(tickBasis){out.addProperty("fromTick",fromTick);out.addProperty("toTick",toTick);}
		else{out.addProperty("fromUtc",from.toString());out.addProperty("toUtc",to.toString());}
		out.addProperty("sampleCount",samples);out.addProperty("gapEvents",gaps);out.addProperty("rejectedIntervals",rejectedIntervals);out.addProperty("incompleteActiveTailIgnored",incompleteTail);
		out.addProperty("coverageStatus",gaps>0||rejectedIntervals>0||incompleteTail?"partial":"observed_intervals_only");
		out.addProperty("throughputStatus","unsupported_no_transfer_counter");out.addProperty("productionStatus","unsupported_no_production_counter");
		out.addProperty("method","Previous-sample hold between adjacent samples only; intervals over 30 wall seconds or the tick-gap bound are excluded. No boundary extrapolation; SU is cached stress, not energy.");
		out.addProperty("maxTickGap",maxTickGap);JsonArray array=new JsonArray();
		double requested=tickBasis?(double)(toTick-fromTick):(to.toEpochMilli()-from.toEpochMilli())/1000.0;
		for(Point p:points.values()){
			JsonObject q=new JsonObject();q.add("position",p.pos);q.addProperty("kineticSamples",p.kineticSamples);q.addProperty("observedWeight",p.weight);
			if(requested>0)q.addProperty("kineticCoverageFraction",Math.max(0,Math.min(1,p.weight/requested)));
			if(p.kineticSamples>0){q.addProperty("minRpm",p.minRpm);q.addProperty("maxRpm",p.maxRpm);}
			if(p.weight>0){q.addProperty("averageRpm",p.rpmWeighted/p.weight);q.addProperty("rotationNonzeroDuration",p.running);q.addProperty("zeroRpmDuration",p.stopped);}
			if(p.overloadWeight>0){q.addProperty("overloadObservedDuration",p.overloadWeight);q.addProperty("overloadedDuration",p.overloaded);}
			if(p.stressWeight>0)q.addProperty("averageStressSU",p.stressWeighted/p.stressWeight);if(p.maxStress!=Double.NEGATIVE_INFINITY)q.addProperty("peakStressSU",p.maxStress);
			if(p.capacityWeight>0)q.addProperty("averageCapacitySU",p.capacityWeighted/p.capacityWeight);if(p.maxCapacity!=Double.NEGATIVE_INFINITY)q.addProperty("peakCapacitySU",p.maxCapacity);
			if(p.utilWeight>0)q.addProperty("averageUtilization",p.utilWeighted/p.utilWeight);if(p.maxUtil!=Double.NEGATIVE_INFINITY)q.addProperty("peakUtilization",p.maxUtil);
			q.addProperty("inventoryNetStatus",p.inventoryPairs>0?(p.inventoryGap?"partial_gaps":"observed_sample_pairs_only"):(p.inventorySeen?"insufficient_samples":"unavailable"));
			if(p.inventoryPairs>0){JsonObject n=new JsonObject();p.net.forEach((k,v)->{if(v!=0)n.addProperty(k,v);});q.add("inventoryNetChange",n);}
			array.add(q);
		}out.add("points",array);return out;
	}
	static JsonObject read(Path data,TelemetryWindow window)throws Exception{
		List<Path> files=new ArrayList<>();Path legacy=data.resolve("mechagent-telemetry.jsonl");if(Files.exists(legacy,LinkOption.NOFOLLOW_LINKS))files.add(legacy);
		try(var listing=Files.list(data)){files.addAll(listing.filter(p->p.getFileName().toString().matches("mechagent-telemetry-[0-9]{8}\\.jsonl")).sorted().toList());}
		if(files.size()>4096)throw new IllegalStateException("too many segments");long total=0;
		for(int i=0;i<files.size();i++){Path path=files.get(i);if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("invalid segment");
			long size=Files.size(path);total+=size;if(size>TelemetryJournal.MAX_BYTES||total>512L*1024*1024)throw new IllegalStateException("scan budget");
			byte[] raw=Files.readAllBytes(path);int end=raw.length;if(end>0&&raw[end-1]!='\n'){if(i!=files.size()-1)throw new IllegalStateException("incomplete segment");while(end>0&&raw[end-1]!='\n')end--;window.incompleteTail=true;}
			for(String line:new String(raw,0,end,StandardCharsets.UTF_8).split("\\n")){if(line.isEmpty())continue;if(line.length()>8192)throw new IllegalStateException("invalid record");window.accept(JsonParser.parseString(line).getAsJsonObject());}
		}JsonObject result=window.finish();result.addProperty("consistency","non_atomic_disk_scan");result.addProperty("scanCompletedUtc",Instant.now().toString());return result;
	}
}
