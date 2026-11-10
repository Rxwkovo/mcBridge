package dev.mcpfabric.telemetry;

/** Integer geometry validation independent of Minecraft and of untrusted RPC inputs. */
public final class TelemetryPolicy {
	public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public boolean valid(int maxVolume) {
			long x = (long) maxX - minX + 1, y = (long) maxY - minY + 1, z = (long) maxZ - minZ + 1;
			return x > 0 && y > 0 && z > 0 && x <= maxVolume && y <= maxVolume / x && z <= maxVolume / (x * y);
		}
		public boolean contains(Box b) {
			return b.minX >= minX && b.minY >= minY && b.minZ >= minZ && b.maxX <= maxX && b.maxY <= maxY && b.maxZ <= maxZ;
		}
		public int volume() { return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }
	}
	private TelemetryPolicy() {}
}
