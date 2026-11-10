package dev.mcpfabric.telemetry;

import java.util.ArrayDeque;

/** Per-thread call correlation. Nested calls invalidate all involved observations. */
public final class TransferCallScope<T> {
    private final ThreadLocal<ArrayDeque<Frame<T>>> frames = ThreadLocal.withInitial(ArrayDeque::new);
    public static final class Frame<T> {
        private final T value;
        private boolean valid = true;
        private Frame(T value) { this.value = value; }
    }
    public Frame<T> enter(T value) {
        ArrayDeque<Frame<T>> stack = frames.get();
        Frame<T> frame = new Frame<>(value);
        if (!stack.isEmpty()) {
            for (Frame<T> enclosing : stack) enclosing.valid = false;
            frame.valid = false;
        }
        stack.push(frame);
        return frame;
    }
    /** Returns a value only for an unambiguous active top-level call. */
    public T current() {
        ArrayDeque<Frame<T>> stack = frames.get();
        if (stack.isEmpty()) { frames.remove(); return null; }
        Frame<T> frame = stack.peek();
        return frame.valid ? frame.value : null;
    }
    /** Must run in a finally block, once, on the same thread as enter. */
    public T leave(Frame<T> frame) {
        ArrayDeque<Frame<T>> stack = frames.get();
        if (stack.isEmpty() || stack.peek() != frame) {
            for (Frame<T> active : stack) active.valid = false;
            frames.remove();
            return null;
        }
        stack.pop();
        if (stack.isEmpty()) frames.remove();
        return frame.valid ? frame.value : null;
    }
}
