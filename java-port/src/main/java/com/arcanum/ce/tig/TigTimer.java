package com.arcanum.ce.tig;

/**
 * Timing. Maps {@code tig/timer.h}. Timestamps are milliseconds since process
 * start; durations are milliseconds. (C used tig_timestamp_t / tig_duration_t,
 * mapped to Java {@code long}.)
 */
public final class TigTimer {

    private static long startNanos;

    private TigTimer() {
    }

    public static int init() {
        startNanos = System.nanoTime();
        return 0;
    }

    /** tig_timer_now -- returns current timestamp (ms). The C signature takes an
     *  out-pointer; here we simply return the value. */
    public static long now() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** tig_timer_elapsed */
    public static long elapsed(long start) {
        return now() - start;
    }

    /** tig_timer_between */
    public static long between(long start, long end) {
        return end - start;
    }
}
