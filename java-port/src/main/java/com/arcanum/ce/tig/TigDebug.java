package com.arcanum.ce.tig;

import com.arcanum.ce.runtime.CLib;

/**
 * Debug logging. Maps {@code tig/debug.h} (tig_debug_printf / tig_debug_println).
 */
public final class TigDebug {

    private TigDebug() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    public static void printf(String format, Object... args) {
        System.out.print(CLib.cfmt(format, args));
    }

    public static void println(String string) {
        System.out.println(string);
    }
}
