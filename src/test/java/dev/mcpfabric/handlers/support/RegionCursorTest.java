package dev.mcpfabric.handlers.support;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionCursorTest {
    @Test void orderAndBoundaryOffsets() {
        long volume = RegionCursor.volume(2, 2, 3, 100);
        assertEquals(12, volume);
        assertArrayEquals(new long[]{0,0,0}, RegionCursor.position(0, 2, 3));
        assertArrayEquals(new long[]{1,0,0}, RegionCursor.position(3, 2, 3));
        assertArrayEquals(new long[]{0,1,0}, RegionCursor.position(6, 2, 3));
        assertArrayEquals(new long[]{1,1,2}, RegionCursor.position(11, 2, 3));
        assertTrue(RegionCursor.validOffset(0, volume));
        assertTrue(RegionCursor.validOffset(volume, volume));
        assertFalse(RegionCursor.validOffset(-1, volume));
        assertFalse(RegionCursor.validOffset(volume + 1, volume));
    }
    @Test void rejectsOversizeAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> RegionCursor.volume(Long.MAX_VALUE, 2, 2, 1L << 24));
        assertThrows(IllegalArgumentException.class, () -> RegionCursor.volume(4096, 4096, 2, 1L << 24));
        assertThrows(IllegalArgumentException.class, () -> RegionCursor.volume(0, 1, 1, 100));
    }
}
