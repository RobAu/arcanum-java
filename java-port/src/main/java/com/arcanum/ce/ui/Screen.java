package com.arcanum.ce.ui;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/**
 * A top-level game screen (main menu, character creation, in-game, ...).
 *
 * This is the Java port's stand-in for the C engine's screen flow driven by
 * {@code main_loop} in {@code src/main.c} (which switches between the main menu
 * and gameplay). Screens render with the shared {@link SpriteBatch} between its
 * begin()/end(); coordinates are top-left origin (see {@code TigArt.draw}).
 */
public interface Screen {

    /** Called once when the screen becomes active. */
    default void create() {
    }

    /** Render one frame. {@code batch} is already begun. */
    void render(SpriteBatch batch, BitmapFont font, int width, int height);

    /** Called when the screen is replaced/torn down. */
    default void dispose() {
    }
}
