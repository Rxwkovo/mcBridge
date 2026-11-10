package dev.mcpfabric.bridge;

import java.util.Set;

/** Server-side default-deny policy, shared by HTTP and direct router dispatch. */
public final class AccessPolicy {
	private static final Set<String> BUILD = Set.of("construction.preview", "construction.commit", "construction.query", "construction.undo", "telemetry.create", "telemetry.update", "telemetry.pause", "telemetry.createMeter", "telemetry.stopMeter");
	private AccessPolicy() {}
	public static boolean allowed(String mode, boolean allowAdmin, String method) {
		if ("ADMIN".equals(mode)) return allowAdmin;
		if (!"READ_ONLY".equals(mode) && !"REGION_BUILD".equals(mode)) return false;
		return ReadAccess.allowed(method) || ("REGION_BUILD".equals(mode) && BUILD.contains(method));
	}
}
