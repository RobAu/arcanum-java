package com.arcanum.ce.tig;

/**
 * Window/region management. Maps {@code tig/window.h}. Windows are ints indexing
 * the engine's stack of UI regions, each backed by an off-screen
 * {@link TigVideoBuffer}. Skeleton only (TODO: window stack, z-order, blitting).
 */
public final class TigWindow {

    private TigWindow() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    public static int create(Object windowData, int[] handleOut) {
        throw new UnsupportedOperationException("TODO: TigWindow.create");
    }

    public static int destroy(int handle) {
        return 0;
    }

    public static int fill(int handle, TigRect rect, int color) {
        return 0;
    }

    public static int display() {
        return 0;
    }

    public static int show(int handle) {
        return 0;
    }

    public static int hide(int handle) {
        return 0;
    }
}
