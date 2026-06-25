package com.arcanum.ce.tig;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;

/**
 * Mouse input & cursor. Maps {@code tig/mouse.h}. Backed by libGDX
 * {@code Gdx.input}.
 */
public final class TigMouse {

    /** TigMouseButton */
    public static final int TIG_MOUSE_BUTTON_LEFT = 0;
    public static final int TIG_MOUSE_BUTTON_RIGHT = 1;
    public static final int TIG_MOUSE_BUTTON_MIDDLE = 2;

    private static int cursorArtId = -1;
    private static int offsetX, offsetY;

    private TigMouse() {
    }

    public static int init() {
        return 0;
    }

    public static void exit() {
    }

    public static void ping() {
    }

    public static int getX() {
        return Gdx.input != null ? Gdx.input.getX() : 0;
    }

    public static int getY() {
        return Gdx.input != null ? Gdx.input.getY() : 0;
    }

    public static boolean isPressed(int button) {
        if (Gdx.input == null) {
            return false;
        }
        int gdxButton = switch (button) {
            case TIG_MOUSE_BUTTON_RIGHT -> Buttons.RIGHT;
            case TIG_MOUSE_BUTTON_MIDDLE -> Buttons.MIDDLE;
            default -> Buttons.LEFT;
        };
        return Gdx.input.isButtonPressed(gdxButton);
    }

    public static int cursorSetArtId(int artId) {
        cursorArtId = artId;
        return 0;
    }

    public static int cursorGetArtId() {
        return cursorArtId;
    }

    public static void cursorSetOffset(int x, int y) {
        offsetX = x;
        offsetY = y;
    }
}
