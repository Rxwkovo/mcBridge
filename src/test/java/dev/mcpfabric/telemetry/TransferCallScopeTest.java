package dev.mcpfabric.telemetry;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferCallScopeTest {
    @Test void normalAndRepeatedCompletion() {
        var scope = new TransferCallScope<String>();
        var token = scope.enter("outer");
        assertEquals("outer", scope.current());
        assertEquals("outer", scope.leave(token));
        assertNull(scope.leave(token));
        assertNull(scope.current());
    }
    @Test void nestedCallsInvalidateBothWithoutLosingOuterScope() {
        var scope = new TransferCallScope<String>();
        var outer = scope.enter("outer");
        var inner = scope.enter("inner");
        assertNull(scope.current());
        assertNull(scope.leave(inner));
        assertNull(scope.current());
        assertNull(scope.leave(outer));
        var next = scope.enter("next");
        assertEquals("next", scope.leave(next));
    }
    @Test void outOfOrderCompletionInvalidatesAllAndAllowsRecovery() {
        var scope = new TransferCallScope<String>();
        var outer = scope.enter("outer");
        var inner = scope.enter("inner");
        assertNull(scope.leave(outer));
        assertNull(scope.leave(inner));
        var next = scope.enter("next");
        assertEquals("next", scope.leave(next));
    }
    @Test void finallyCleansUpAfterException() {
        var scope = new TransferCallScope<String>();
        assertThrows(IllegalStateException.class, () -> {
            var frame = scope.enter("failed");
            try { throw new IllegalStateException(); } finally { scope.leave(frame); }
        });
        assertNull(scope.current());
        var next = scope.enter("next");
        assertEquals("next", scope.leave(next));
    }
}
