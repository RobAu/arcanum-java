package com.arcanum.ce.tig;

/**
 * TIG engine facade. Maps {@code tig/core.h} (tig_init / tig_exit / tig_ping /
 * tig_set_active / tig_get_active). Owns the lifecycle of the subsystems.
 */
public final class Tig {

    private static boolean active = true;
    private static boolean initialized = false;

    private Tig() {
    }

    /** tig_init */
    public static int init() {
        if (initialized) {
            return 2; // TIG_ERR_ALREADY_INITIALIZED
        }
        TigMemory.init();
        TigTimer.init();
        TigDebug.println("TIG runtime initialized (Java/libGDX backend)");
        initialized = true;
        return 0;
    }

    /** tig_exit */
    public static void exit() {
        initialized = false;
    }

    /** tig_ping -- called once per frame to service the subsystems. */
    public static void ping() {
        TigMessage.ping();
        TigSound.ping();
    }

    public static void setActive(boolean isActive) {
        active = isActive;
    }

    public static boolean getActive() {
        return active;
    }
}
