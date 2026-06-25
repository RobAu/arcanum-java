package com.arcanum.ce.tig;

/**
 * UI buttons. Maps {@code tig/button.h}. Button handles are ints. Behaviour is
 * driven by the window/message system; this is a skeleton (TODO: full button
 * state machine + hit-testing) so transpiled UI code compiles.
 */
public final class TigButton {

    /** TigButtonState */
    public static final int TIG_BUTTON_STATE_RELEASED = 0;
    public static final int TIG_BUTTON_STATE_PRESSED = 1;
    public static final int TIG_BUTTON_STATE_DISABLED = 2;

    private TigButton() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    public static int create(Object buttonData, int[] handleOut) {
        throw new UnsupportedOperationException("TODO: TigButton.create");
    }

    public static int destroy(int handle) {
        return 0;
    }

    public static int show(int handle) {
        return 0;
    }

    public static int hide(int handle) {
        return 0;
    }
}
