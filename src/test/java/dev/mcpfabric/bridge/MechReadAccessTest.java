package dev.mcpfabric.bridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MechReadAccessTest {
    @Test void observationAllowedAndMutationDenied() {
        assertTrue(ReadAccess.allowed("world.listRegisteredBlocks"));
        assertTrue(ReadAccess.allowed("world.inspectBlockEntity"));
        assertTrue(ReadAccess.allowed("world.inspectCreateKinetic"));
        assertTrue(ReadAccess.allowed("world.inspectCreateNetwork"));
        assertFalse(ReadAccess.allowed("world.setBlock"));
        assertFalse(ReadAccess.allowed("command.run"));
        assertFalse(ReadAccess.allowed("world.notRegistered"));
    }
}
