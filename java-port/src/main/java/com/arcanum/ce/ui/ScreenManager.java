package com.arcanum.ce.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A tiny screen stack so screens can push/pop one another (e.g. the main menu
 * opening the in-game world view, then Escape returning to it). Stands in for
 * the C engine's {@code main_loop} screen switching in {@code src/main.c}.
 *
 * <p>{@link #push} makes a new screen active (after {@code create()}); {@link
 * #pop} disposes it and reveals the one beneath. {@link ArcanumGame} renders
 * {@link #current()} each frame.
 */
public final class ScreenManager {

    private static final Deque<Screen> STACK = new ArrayDeque<>();

    private ScreenManager() {
    }

    public static void push(Screen screen) {
        screen.create();
        STACK.push(screen);
    }

    /** Pop and dispose the active screen; no-op if only one (or none) remains. */
    public static void pop() {
        if (STACK.size() <= 1) {
            return;
        }
        STACK.pop().dispose();
    }

    public static Screen current() {
        return STACK.peek();
    }

    /** Dispose every screen (app shutdown). */
    public static void disposeAll() {
        while (!STACK.isEmpty()) {
            STACK.pop().dispose();
        }
    }
}
