package dev.mcpfabric.construction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionPolicyTest {
	@Test void recoveryReportsObservationWithoutAssumingCause() {
		assertEquals("unavailable_unloaded", ConstructionPolicy.classifyRecovery(false, false, false));
		assertEquals("matches_before", ConstructionPolicy.classifyRecovery(true, true, false));
		assertEquals("matches_after", ConstructionPolicy.classifyRecovery(true, false, true));
		assertEquals("conflict", ConstructionPolicy.classifyRecovery(true, false, false));
		assertEquals("uncertain_identical_states", ConstructionPolicy.classifyRecovery(true, true, true));
	}
	@org.junit.jupiter.api.Test void partialEditsAreNeverReportedAsComplete() {
		org.junit.jupiter.api.Assertions.assertEquals("partial", ConstructionPolicy.completedStatus(false, java.util.List.of("applied", "state_conflict")));
		org.junit.jupiter.api.Assertions.assertEquals("partial", ConstructionPolicy.completedStatus(true, java.util.List.of("undone", "unloaded_or_outside")));
		org.junit.jupiter.api.Assertions.assertEquals("completed", ConstructionPolicy.completedStatus(false, java.util.List.of("applied")));
	}
	@Test void inclusiveBoundsAndOverflow() {
		var b = new ConstructionPolicy.Bounds(Integer.MIN_VALUE, -64, 0, Integer.MAX_VALUE, 320, 15);
		assertTrue(b.contains(Integer.MIN_VALUE, -64, 0));
		assertTrue(b.contains(Integer.MAX_VALUE, 320, 15));
		assertFalse(b.contains(0, 321, 0));
		assertThrows(IllegalArgumentException.class, () -> new ConstructionPolicy.Bounds(1, 0, 0, 0, 0, 0));
	}
	@Test void allowlistAndKeysAreStrict() {
		assertTrue(ConstructionPolicy.allowedBlock("create:shaft"));
		assertTrue(ConstructionPolicy.allowedBlock("minecraft:stone"));
		assertFalse(ConstructionPolicy.allowedBlock("minecraft:chest"));
		assertFalse(ConstructionPolicy.allowedBlock("minecraft:command_block"));
		assertFalse(ConstructionPolicy.allowedBlock("create:mechanical_press", java.util.Set.of()));
		assertTrue(ConstructionPolicy.allowedBlock("create:mechanical_press", java.util.Set.of("create:mechanical_press")));
		assertFalse(ConstructionPolicy.allowedBlock("minecraft:command_block", java.util.Set.of("create:mechanical_press")));
		assertTrue(ConstructionPolicy.validKey("job-42:step.1"));
		assertFalse(ConstructionPolicy.validKey("../not permitted"));
		assertFalse(ConstructionPolicy.validKey("x".repeat(97)));
	}
}
