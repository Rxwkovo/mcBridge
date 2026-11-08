package dev.mcpfabric.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Unsided capability views only. No insert, extract, fill or drain calls are made. */
final class NeoForgeCapabilities {
    private NeoForgeCapabilities() {}

    static JsonObject inspect(ServerLevel level, BlockPos pos) {
        JsonObject out = new JsonObject();
        out.addProperty("scope", "unsided (null); sided capabilities may differ or be unavailable");
        JsonObject items = new JsonObject();
        try {
            var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) items.addProperty("status", "unavailable");
            else {
                items.addProperty("status", "available");
                int slots = handler.getSlots();
                items.addProperty("slots", slots);
                int count = Math.min(Math.max(slots, 0), 64);
                items.addProperty("truncated", slots > count);
                JsonArray entries = new JsonArray();
                for (int i = 0; i < count; i++) {
                    var stack = handler.getStackInSlot(i);
                    if (stack.isEmpty()) continue;
                    JsonObject entry = new JsonObject();
                    entry.addProperty("slot", i);
                    entry.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    entry.addProperty("count", stack.getCount());
                    entries.add(entry);
                }
                items.add("contents", entries);
            }
        } catch (RuntimeException e) { items = unavailable("read_error"); }
        out.add("items", items);
        JsonObject fluids = new JsonObject();
        try {
            var handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null);
            if (handler == null) fluids.addProperty("status", "unavailable");
            else {
                fluids.addProperty("status", "available");
                int tanks = handler.getTanks();
                fluids.addProperty("tanks", tanks);
                int count = Math.min(Math.max(tanks, 0), 16);
                fluids.addProperty("truncated", tanks > count);
                JsonArray entries = new JsonArray();
                for (int i = 0; i < count; i++) {
                    var stack = handler.getFluidInTank(i);
                    JsonObject entry = new JsonObject();
                    entry.addProperty("tank", i);
                    entry.addProperty("fluid", BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString());
                    entry.addProperty("amount", stack.getAmount());
                    entry.addProperty("capacity", handler.getTankCapacity(i));
                    entries.add(entry);
                }
                fluids.add("contents", entries);
            }
        } catch (RuntimeException e) { fluids = unavailable("read_error"); }
        out.add("fluids", fluids);
        JsonObject energy = new JsonObject();
        try {
            var handler = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null);
            if (handler == null) energy.addProperty("status", "unavailable");
            else {
                energy.addProperty("status", "available");
                energy.addProperty("stored", handler.getEnergyStored());
                energy.addProperty("capacity", handler.getMaxEnergyStored());
                energy.addProperty("unit", "FE");
            }
        } catch (RuntimeException e) { energy = unavailable("read_error"); }
        out.add("energy", energy);
        return out;
    }

    private static JsonObject unavailable(String status) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        return result;
    }
}
