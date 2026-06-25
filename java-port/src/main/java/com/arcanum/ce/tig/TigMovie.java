package com.arcanum.ce.tig;

/**
 * Cutscene playback. Maps {@code tig/movie.h}. The C engine played Bink
 * ({@code .bik}) movies; libGDX has no built-in Bink decoder, so this is a
 * placeholder (TODO: integrate a video decoder or pre-convert assets).
 */
public final class TigMovie {

    private TigMovie() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    /** tig_movie_play */
    public static int play(String path, int movieFlags, int soundTrack) {
        TigDebug.println("TODO: movie playback skipped: " + path);
        return 0;
    }

    public static boolean isPlaying() {
        return false;
    }
}
