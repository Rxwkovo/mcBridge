package dev.mcpfabric.neoforge.meter;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mcpfabric.neoforge.meterprobe.HopperProbe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Version-specific interception point; this initial probe delegates without changing behavior. */
@Mixin(targets = "net.neoforged.neoforge.items.VanillaInventoryCodeHooks", remap = false)
abstract class HopperHookMixin {
	@WrapMethod(method = "extractHook", remap = false, require = 0)
	private static Boolean mechagent$probe(Level level, Hopper hopper, Operation<Boolean> original) {
		var token = HopperProbe.begin(level, hopper);
		try {
			Boolean result = original.call(level, hopper);
			HopperProbe.complete(result);
			return result;
		} finally { HopperProbe.clear(token); }
	}
	@WrapOperation(method = "lambda$extractHook$0", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", remap = false), remap = false, require = 0)
	private static ItemStack mechagent$probeExtraction(IItemHandler handler, int slot, int amount, boolean simulate, Operation<ItemStack> original) {
		ItemStack result = original.call(handler, slot, amount, simulate);
		HopperProbe.capture(handler, simulate, result);
		return result;
	}
}
