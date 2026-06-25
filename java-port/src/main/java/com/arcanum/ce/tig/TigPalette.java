package com.arcanum.ce.tig;

/**
 * Palettes. Maps {@code tig/palette.h}. The C engine used 8-bit palettized art;
 * a palette is 256 packed colors. Backed by an int[256] here. Skeleton only.
 */
public final class TigPalette {

    public final int[] colors = new int[256];

    /** tig_palette_create */
    public static TigPalette create() {
        return new TigPalette();
    }

    public static void destroy(TigPalette palette) {
    }

    public static void fill(TigPalette palette, int color) {
        java.util.Arrays.fill(palette.colors, color);
    }

    public static void copy(TigPalette dst, TigPalette src) {
        System.arraycopy(src.colors, 0, dst.colors, 0, 256);
    }
}
