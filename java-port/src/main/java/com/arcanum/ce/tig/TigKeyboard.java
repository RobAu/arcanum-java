package com.arcanum.ce.tig;

import com.badlogic.gdx.Gdx;

/**
 * Keyboard input. Maps {@code tig/kb.h}. The C engine used SDL scancodes; the
 * port maps those onto libGDX {@code Input.Keys} as the key set is filled in.
 */
public final class TigKeyboard {

    private TigKeyboard() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    /** tig_kb_is_key_pressed -- argument is a libGDX Input.Keys code in the port
     *  (the SDL_Scancode -> Input.Keys mapping table is TODO). */
    public static boolean isKeyPressed(int key) {
        return Gdx.input != null && Gdx.input.isKeyPressed(key);
    }

    public static boolean getModifier(int keymod) {
        return false; // TODO: map SDL_Keymod onto Input.Keys modifiers
    }
}
