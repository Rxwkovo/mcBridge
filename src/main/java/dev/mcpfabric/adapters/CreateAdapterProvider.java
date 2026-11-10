package dev.mcpfabric.adapters;

import dev.mcpfabric.platform.Platform;

/** Selects optional Create integrations using loader metadata, without linking Create classes. */
public final class CreateAdapterProvider {
	public static final String SUPPORTED_VERSION = "6.0.10";
	private static final KineticObserver KINETIC = new CreateKineticAdapter();
	private static final KineticNetworkObserver NETWORK = new CreateNetworkAdapter();
	private static final class Moving {
		private static final MovingStructureObserver OBSERVER = new Create610MovingObserver();
	}

	private CreateAdapterProvider() {}

	public static String version(Platform platform) {
		return platform.loadedMods().get("create");
	}

	public static String status(Platform platform) {
		String version = version(platform);
		if (version == null) return "create_not_loaded";
		if (!"neoforge".equals(platform.loader()) || !SUPPORTED_VERSION.equals(version)
				|| !"1.21.1".equals(platform.minecraftVersion())) return "unsupported_version";
		return "detected";
	}

	public static KineticObserver kinetic() { return KINETIC; }
	public static KineticNetworkObserver network() { return NETWORK; }
	public static MovingStructureObserver moving() { return Moving.OBSERVER; }
}
