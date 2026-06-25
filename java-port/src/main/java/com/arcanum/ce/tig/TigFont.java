package com.arcanum.ce.tig;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;

/**
 * Font rendering & measurement. Maps {@code tig/font.h}. Backed by a libGDX
 * {@link BitmapFont} (gdx-freetype can generate one from the game's TTF/fonts).
 */
public final class TigFont {

    private static BitmapFont current;
    private static final GlyphLayout LAYOUT = new GlyphLayout();

    private TigFont() {
    }

    public static int init() {
        current = new BitmapFont();
        return 0;
    }

    public static void exit() {
        if (current != null) {
            current.dispose();
            current = null;
        }
    }

    /** Measure text into a TigRect (width/height). Mirrors tig_font_measure. */
    public static void measure(String text, TigRect out) {
        if (current == null || text == null) {
            return;
        }
        LAYOUT.setText(current, text);
        out.width = (int) Math.ceil(LAYOUT.width);
        out.height = (int) Math.ceil(LAYOUT.height);
    }

    public static BitmapFont current() {
        return current;
    }
}
