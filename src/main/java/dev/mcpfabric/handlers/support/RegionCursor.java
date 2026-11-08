package dev.mcpfabric.handlers.support;

/** Arithmetic for a region cursor, ordered Y then X then Z. */
public final class RegionCursor {
    private RegionCursor() {}
    public static long volume(long x, long y, long z, long max) {
        if (x <= 0 || y <= 0 || z <= 0 || x > max || y > max / x || z > max / (x * y))
            throw new IllegalArgumentException("Region exceeds maximum volume");
        return x * y * z;
    }
    public static long[] position(long index, long dx, long dz) {
        return new long[] {(index / dz) % dx, index / (dx * dz), index % dz};
    }
    public static boolean validOffset(long offset, long volume) { return offset >= 0 && offset <= volume; }
}
