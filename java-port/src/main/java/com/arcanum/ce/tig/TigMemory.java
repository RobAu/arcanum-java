package com.arcanum.ce.tig;

/**
 * Memory subsystem. Maps {@code tig/memory.h}. In C this wrapped malloc/free with
 * leak tracking; under the JVM allocation is automatic, so most of this becomes a
 * no-op kept only so transpiled {@code tig_memory_*} calls have a target.
 */
public final class TigMemory {

    private TigMemory() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    /** tig_memory_strdup -- strings are immutable values in Java. */
    public static String strdup(String s) {
        return s;
    }

    /** tig_memory_alloc / calloc / realloc / free are unrepresentable as raw
     *  byte buffers in idiomatic Java; the transpiler turns the surrounding C
     *  allocation idioms into ordinary object/array construction instead. */
}
