package dev.mcpfabric.telemetry;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HopperTransferEvidenceTest {
	@Test void acceptsConsistentExtractionAndHopperIncrease() {
		var result=HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",1,Map.of(),Map.of("create:iron_sheet",1L));
		assertTrue(result.consistent());assertEquals("create:iron_sheet",result.itemId());assertEquals(1,result.count());
	}
	@Test void rejectsSimulationFailureWrongSourceAndDestinationMismatch() {
		var before=Map.<String,Long>of();var after=Map.of("create:iron_sheet",1L);
		assertFalse(HopperTransferEvidence.validate(true,true,true,true,"create:iron_sheet",1,before,after).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,false,true,"create:iron_sheet",1,before,after).consistent());
		assertFalse(HopperTransferEvidence.validate(false,false,true,true,"create:iron_sheet",1,before,after).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,true,false,"create:iron_sheet",1,before,after).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",0,before,after).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",1,before,Map.of()).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",1,before,Map.of("minecraft:iron_ingot",1L)).consistent());
		assertNull(HopperTransferEvidence.validate(true,true,true,true,"create:iron_sheet",1,before,after).count());
	}
	@Test void refusesMultiItemOrConcurrentUnrelatedChanges() {
		assertFalse(HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",2,Map.of(),Map.of("create:iron_sheet",2L)).consistent());
		assertFalse(HopperTransferEvidence.validate(true,false,true,true,"create:iron_sheet",1,Map.of("minecraft:iron_ingot",2L),Map.of("minecraft:iron_ingot",1L,"create:iron_sheet",1L)).consistent());
	}
}
