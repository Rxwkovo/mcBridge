package dev.mcpfabric.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
	@Test void defaultsAndUnknownModesFailClosed() {
		assertTrue(AccessPolicy.allowed("READ_ONLY", false, "world.getBlock"));
		assertFalse(AccessPolicy.allowed("READ_ONLY", true, "construction.commit"));
		assertFalse(AccessPolicy.allowed(null, true, "world.getBlock"));
		assertFalse(AccessPolicy.allowed("ADMIN", false, "command.run"));
		assertTrue(AccessPolicy.allowed("ADMIN", true, "command.run"));
	}
	@Test void buildersCannotUseLegacyOrFutureMutations() {
		for (String method : new String[]{"world.setBlock","world.fill","command.run","entities.summon","entities.remove","interact.placeBlock","supervised.setBlock","supervised.command","chat.send","future.write"})
			assertFalse(AccessPolicy.allowed("REGION_BUILD", true, method), method);
		assertFalse(AccessPolicy.allowed("READ_ONLY", false, "telemetry.createMeter"));
		assertFalse(AccessPolicy.allowed("READ_ONLY", false, "telemetry.stopMeter"));
		assertTrue(AccessPolicy.allowed("READ_ONLY", false, "telemetry.queryMeter"));
		assertTrue(AccessPolicy.allowed("REGION_BUILD", false, "telemetry.createMeter"));
		assertTrue(AccessPolicy.allowed("REGION_BUILD", false, "construction.preview"));
		assertTrue(AccessPolicy.allowed("REGION_BUILD", false, "construction.commit"));
		assertTrue(AccessPolicy.allowed("REGION_BUILD", false, "construction.undo"));
	}
}
