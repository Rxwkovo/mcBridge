package dev.mcpfabric.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryPolicyTest {
	@Test void validatesGeometryWithoutIntegerOverflow() {
		var area = new TelemetryPolicy.Box(-10, 0, -10, 10, 10, 10);
		assertTrue(area.contains(new TelemetryPolicy.Box(-1, 1, -1, 2, 3, 2)));
		assertFalse(area.contains(new TelemetryPolicy.Box(-11, 1, 0, 2, 3, 2)));
		assertFalse(new TelemetryPolicy.Box(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0).valid(64));
		assertFalse(new TelemetryPolicy.Box(2, 0, 0, 1, 0, 0).valid(64));
		assertTrue(new TelemetryPolicy.Box(0, 0, 0, 3, 3, 3).valid(64));
	}
}
