package com.arcanum.ce.tig;

import com.badlogic.gdx.graphics.Color;

/**
 * Color packing/blending. Maps {@code tig/color.h}. A {@code tig_color_t} is a
 * packed RGBA value held in a Java {@code int} (note: C treated it as unsigned;
 * here it is a signed int with identical bit pattern).
 */
public final class TigColor {

    private TigColor() {
    }

    public static int rgba(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int rgb(int r, int g, int b) {
        return rgba(r, g, b, 0xFF);
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int alpha(int color) {
        return (color >> 24) & 0xFF;
    }

    /** tig_color_rgb_to_grayscale */
    public static int rgbToGrayscale(int color) {
        int y = (red(color) * 30 + green(color) * 59 + blue(color) * 11) / 100;
        return rgba(y, y, y, alpha(color));
    }

    /** Convert a packed TIG color into a libGDX {@link Color}. */
    public static Color toGdx(int color, Color out) {
        out.set(red(color) / 255f, green(color) / 255f, blue(color) / 255f,
                alpha(color) / 255f);
        return out;
    }
}
