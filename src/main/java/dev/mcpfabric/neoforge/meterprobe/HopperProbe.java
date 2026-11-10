package dev.mcpfabric.neoforge.meterprobe;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.telemetry.HopperTransferEvidence;
import dev.mcpfabric.telemetry.TransferCallScope;
import dev.mcpfabric.telemetry.FactoryTelemetry;
import net.neoforged.fml.ModList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.*;

/** Diagnostic-only probe; never changes inventory and is inactive without an explicit JVM flag. */
public final class HopperProbe {
	public static boolean supported() {
		try { return ModList.get().getModContainerById("create").map(m -> m.getModInfo().getVersion().toString().equals("6.0.10")).orElse(false)
				&& ModList.get().getModContainerById("neoforge").map(m -> m.getModInfo().getVersion().toString().equals("21.1.248")).orElse(false); }
		catch (RuntimeException | LinkageError e) { return false; }
	}
	private static final TransferCallScope<Frame> SCOPE = new TransferCallScope<>();
	private HopperProbe() {}
	public static final class Frame {
		Hopper hopper; Level level; ItemStack[] before; ItemStack extracted; boolean source, ambiguous;
	}
	public static TransferCallScope.Frame<Frame> begin(Level level, Hopper hopper) {
		Frame f = null;
		try {
			if (supported() && !level.isClientSide && level.getServer()!=null && level.getServer().isSameThread()
					&& hopper instanceof HopperBlockEntity be && hopper.getContainerSize()==5
					&& FactoryTelemetry.meterCandidate(level.getServer(), level.dimension().location().toString(), be.getBlockPos())
					&& "create:depot".equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(be.getBlockPos().above()).getBlock()).toString())) {
				f=new Frame(); f.hopper=hopper; f.level=level; f.before=snapshot(hopper);
			}
		} catch (RuntimeException | LinkageError ignored) { f=null; }
		return SCOPE.enter(f);
	}
	public static void capture(IItemHandler handler, boolean simulated, ItemStack returned) {
		if (simulated) return;
		try { Frame f=SCOPE.current(); if (f==null) return;
			if (f.extracted!=null) { f.ambiguous=true; return; }
			f.source=handler.getClass().getName().equals("com.simibubi.create.content.logistics.depot.DepotItemHandler");
			f.extracted=returned.copy();
		} catch (RuntimeException | LinkageError ignored) { }
	}
	public static void complete(Boolean success) {

		try { Frame f=SCOPE.current(); if (f==null||f.extracted==null||f.ambiguous) return;
			ItemStack[] after=snapshot(f.hopper); String id=BuiltInRegistries.ITEM.getKey(f.extracted.getItem()).toString();
			long beforeSame=0,afterSame=0; for(ItemStack s:f.before)if(ItemStack.isSameItemSameComponents(s,f.extracted))beforeSame+=s.getCount();
			for(ItemStack s:after)if(ItemStack.isSameItemSameComponents(s,f.extracted))afterSame+=s.getCount();
			var evidence=HopperTransferEvidence.validate(f.source,false,Boolean.TRUE.equals(success),afterSame-beforeSame==f.extracted.getCount(),id,f.extracted.getCount(),counts(f.before),counts(after));
			if (evidence.consistent()) FactoryTelemetry.recordMeter(f.level.getServer(), f.level.dimension().location().toString(), ((HopperBlockEntity) f.hopper).getBlockPos(), evidence.itemId(), f.level.getGameTime());
		} catch (RuntimeException | LinkageError ignored) { }
	}
	public static void clear(TransferCallScope.Frame<Frame> token) { SCOPE.leave(token); }
	private static ItemStack[] snapshot(Hopper hopper) { ItemStack[] a=new ItemStack[5];for(int i=0;i<5;i++)a[i]=hopper.getItem(i).copy();return a; }
	private static Map<String,Long> counts(ItemStack[] slots) { Map<String,Long> m=new HashMap<>();for(ItemStack s:slots)if(!s.isEmpty())m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),(long)s.getCount(),Long::sum);return m; }
}
