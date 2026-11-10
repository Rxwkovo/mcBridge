package dev.mcpfabric.handlers;

import com.google.gson.JsonElement;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.*;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.telemetry.FactoryTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** RPC access to the local, bounded monitoring service. */
public final class TelemetryHandlers {
    private TelemetryHandlers() {}
    private interface Work { JsonElement run() throws RpcException; }
    private static JsonElement call(Work work) throws RpcException {
        MinecraftServer server = ServerHolder.get();
        if (server == null) throw RpcException.noServer();
        return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> { Gates.vision(); return work.run(); });
    }
    private static BlockPos position(RpcContext c, String key) throws RpcException {
        RpcContext p = new RpcContext(key, c.getObject(key));
        return new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z"));
    }
    public static void register(RpcRouter router) {
        router.register("telemetry.createMeter", c -> call(() -> FactoryTelemetry.createMeter(c.getString("areaId"), c.getString("dimension"), position(c,"hopper"), c.getString("itemId"))));
        router.register("telemetry.queryMeter", c -> call(() -> FactoryTelemetry.queryMeter(c.getString("meterId"))));
        router.register("telemetry.stopMeter", c -> call(() -> FactoryTelemetry.stopMeter(c.getString("meterId"))));
        router.register("telemetry.create", c -> call(() -> FactoryTelemetry.create(c.getString("name"), c.getString("areaId"), c.getString("dimension"), position(c,"min"), position(c,"max"), c.getInt("intervalTicks"), c.optBool("includeCapabilities",false), c.optBool("includeKinetic",false), c.optBool("includeMovingStructures",false))));
        router.register("telemetry.update", c -> call(() -> FactoryTelemetry.update(c.getString("monitorId"), c.getString("name"), c.getString("areaId"), c.getString("dimension"), position(c,"min"), position(c,"max"), c.getInt("intervalTicks"), c.optBool("includeCapabilities",false), c.optBool("includeKinetic",false), c.optBool("includeMovingStructures",false))));
		router.register("telemetry.pause", c -> call(() -> {
			Boolean paused = c.optBoolean("paused");
			if (paused == null) throw RpcException.badRequest("Missing required param 'paused'.");
			return FactoryTelemetry.pause(c.getString("monitorId"), paused);
		}));
        router.register("telemetry.list", c -> call(FactoryTelemetry::list));
        router.register("telemetry.get", c -> call(() -> FactoryTelemetry.get(c.getString("monitorId"))));
        router.register("telemetry.history", c -> call(() -> {
            if (c.optBool("disk", false)) return FactoryTelemetry.historyFromDisk(c.getString("monitorId"), c.optLong("sinceSequence",0), c.optInt("limit",50), c.optString("fromUtc",null), c.optString("toUtc",null), c.optString("eventType",null));
            if (c.has("fromUtc") || c.has("toUtc") || c.has("eventType")) throw RpcException.badRequest("History filters require disk=true.");
            return FactoryTelemetry.history(c.getString("monitorId"), c.optLong("sinceSequence",0), c.optInt("limit",50));
        }));
        router.register("telemetry.metrics", c -> call(() -> {
            if (!c.has("windowSeconds") && !c.has("fromUtc") && !c.has("toUtc") && !c.has("timeBasis") && !c.has("fromTick") && !c.has("toTick")) return FactoryTelemetry.metrics(c.getString("monitorId"));
            return FactoryTelemetry.metricsWindow(c.getString("monitorId"), c.has("windowSeconds") ? c.getInt("windowSeconds") : null, c.optString("fromUtc",null), c.optString("toUtc",null), c.optString("timeBasis","wall"), c.has("fromTick") ? c.optLong("fromTick",0) : null, c.has("toTick") ? c.optLong("toTick",0) : null);
        }));
		router.register("telemetry.kineticHistory", c -> call(() -> FactoryTelemetry.kineticHistory(c.getString("monitorId"), c.optLong("sinceSequence",0), c.optInt("limit",50), c.optString("fromUtc",null), c.optString("toUtc",null))));
		router.register("telemetry.comparePeriods", c -> call(() -> FactoryTelemetry.comparePeriods(c.getString("monitorId"),c.getString("firstFromUtc"),c.getString("firstToUtc"),c.getString("secondFromUtc"),c.getString("secondToUtc"))));
    }
}
