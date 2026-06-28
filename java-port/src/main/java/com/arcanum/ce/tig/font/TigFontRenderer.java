package com.arcanum.ce.tig.font;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

import com.arcanum.ce.tig.TigArt;
import com.arcanum.ce.tig.art.ArtFile;
import com.arcanum.ce.tig.art.ArtId;

/**
 * Renders text with a real Arcanum font, ported from {@code first_party/tig/src/font.c}.
 *
 * A TIG font is an ART whose frames are glyphs: the glyph for character
 * {@code ch} is frame {@code ch - 31} (so space (32) is frame 1, 'A' (65) is
 * frame 34). Each glyph frame is full line-height; the pen advances by the
 * glyph's {@code hot_x} (the last char advances by its width, per
 * {@code tig_font_measure}). Glyphs are tinted via the SpriteBatch color.
 *
 * Fonts are loaded by interface art number (the menu uses {@code morph15font}
 * = 27). Glyph textures are cached through {@link TigArt#texture}.
 */
public final class TigFontRenderer {

    private static final int FIRST_GLYPH_CHAR = 31; // frame = ch - 31

    private final ArtFile font;
    private final String cacheKey;
    private final int paletteSlot;
    private final int lineHeight;

    private TigFontRenderer(ArtFile font, String cacheKey) {
        this.font = font;
        this.cacheKey = cacheKey;
        int pal = font.firstPaletteSlot();
        this.paletteSlot = pal < 0 ? 0 : pal;
        this.lineHeight = font.frames[0][0].height;
    }

    /** Load a font by its interface art number, or null if unavailable. */
    public static TigFontRenderer load(int interfaceArtNum) {
        int aid = ArtId.interfaceIdCreate(interfaceArtNum, 0, 0, 0);
        String path = TigArt.buildPath(aid);
        if (path == null) {
            return null;
        }
        ArtFile f = TigArt.load(path);
        return f != null ? new TigFontRenderer(f, "font" + interfaceArtNum) : null;
    }

    public int lineHeight() {
        return lineHeight;
    }

    private boolean hasGlyph(int frame) {
        return frame >= 0 && frame < font.numFrames;
    }

    /** Measure the pixel width of {@code str} (tig_font_measure, single line). */
    public int measureWidth(String str) {
        int width = 0;
        for (int i = 0; i < str.length(); i++) {
            int frame = (str.charAt(i) & 0xFF) - FIRST_GLYPH_CHAR;
            if (!hasGlyph(frame)) {
                continue;
            }
            ArtFile.Frame fr = font.frames[0][frame];
            width += (i + 1 < str.length()) ? fr.hotX : fr.width;
        }
        return width;
    }

    /**
     * Draw {@code str} with its top-left at (x, yTop) in a top-left-origin space
     * of the given window height, tinted with {@code tint}.
     */
    public void draw(SpriteBatch batch, String str, float x, float yTop,
                     int screenHeight, Color tint) {
        Color prev = batch.getColor().cpy();
        batch.setColor(tint);
        float pen = x;
        for (int i = 0; i < str.length(); i++) {
            int frame = (str.charAt(i) & 0xFF) - FIRST_GLYPH_CHAR;
            if (!hasGlyph(frame)) {
                continue;
            }
            ArtFile.Frame fr = font.frames[0][frame];
            TigArt.draw(batch, cacheKey, font, 0, frame, paletteSlot,
                    pen, yTop, screenHeight, false);
            pen += fr.hotX;
        }
        batch.setColor(prev);
    }
}
