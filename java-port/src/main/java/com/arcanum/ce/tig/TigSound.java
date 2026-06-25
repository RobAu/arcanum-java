package com.arcanum.ce.tig;

import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;

/**
 * Audio. Maps {@code tig/sound.h} (which the C engine implemented on top of
 * Miles/SDL_mixer). Short effects use libGDX {@link Sound}; streamed music uses
 * {@link Music}. Sound handles are ints indexing the maps below.
 */
public final class TigSound {

    /** TigSoundType */
    public static final int TIG_SOUND_TYPE_EFFECT = 0;
    public static final int TIG_SOUND_TYPE_VOICE = 1;
    public static final int TIG_SOUND_TYPE_MUSIC = 2;

    private static final Map<Integer, Sound> EFFECTS = new HashMap<>();
    private static final Map<Integer, Music> STREAMS = new HashMap<>();
    private static int nextHandle = 1;
    private static boolean initialized;

    private TigSound() {
    }

    public static int init() {
        initialized = true;
        return 0;
    }

    public static void exit() {
        for (Sound s : EFFECTS.values()) s.dispose();
        for (Music m : STREAMS.values()) m.dispose();
        EFFECTS.clear();
        STREAMS.clear();
        initialized = false;
    }

    public static void ping() {
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** tig_sound_create -- allocates a handle. */
    public static int create(int[] handleOut, int type) {
        int h = nextHandle++;
        if (handleOut != null) {
            handleOut[0] = h;
        }
        return 0;
    }

    /** tig_sound_play -- load and play a clip from a path. */
    public static int play(int handle, String path, int id) {
        if (Gdx.files == null || path == null) {
            return 1;
        }
        FileHandle fh = Gdx.files.internal(path);
        if (!fh.exists()) {
            return 1;
        }
        Sound s = Gdx.audio.newSound(fh);
        EFFECTS.put(handle, s);
        s.play();
        return 0;
    }

    public static void stop(int handle, int fadeDuration) {
        Sound s = EFFECTS.remove(handle);
        if (s != null) s.dispose();
        Music m = STREAMS.remove(handle);
        if (m != null) m.dispose();
    }

    public static void stopAll(int fadeDuration) {
        for (Integer h : new java.util.ArrayList<>(EFFECTS.keySet())) {
            stop(h, fadeDuration);
        }
        for (Integer h : new java.util.ArrayList<>(STREAMS.keySet())) {
            stop(h, fadeDuration);
        }
    }
}
