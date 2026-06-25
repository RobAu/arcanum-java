package com.arcanum.ce.tig.art;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoder for Arcanum {@code .ART} sprite/animation files.
 *
 * Ported 1:1 from the validated Python reference {@code c2java/tools/art_decode.py}
 * (itself recovered from {@code first_party/tig/src/art.c}). Pure Java with no
 * libGDX dependency so it is unit-testable without a GL context; rendering to a
 * {@code Pixmap}/{@code Texture} lives in {@link com.arcanum.ce.tig.TigArt}.
 *
 * Format: little-endian, 8-bpp palette-indexed. Palette index 0 is the color key
 * (rendered transparent). See ART_PORT_PLAN.md for the full byte layout.
 */
public final class ArtFile {

    public static final int HEADER_SIZE = 132;
    public static final int FRAME_REC = 28;
    public static final int MAX_PALETTES = 4;
    public static final int MAX_ROTATIONS = 8;

    /** One frame: dimensions, hotspot/offset, and width*height palette indices. */
    public static final class Frame {
        public int width;
        public int height;
        public int dataSize;
        public int hotX;
        public int hotY;
        public int offsetX;
        public int offsetY;
        public byte[] indices = new byte[0];
    }

    public int flags;
    public int fps;
    public int bpp;
    public int actionFrame;
    public int numFrames;
    public int numRotations;
    public final int[] palettePresent = new int[MAX_PALETTES];
    /** Per slot (when present): 256 packed {@code 0x00RRGGBB} colors, else null. */
    public final int[][] palettes = new int[MAX_PALETTES][];
    /** {@code frames[rotation][frame]}. */
    public Frame[][] frames;

    private ArtFile() {
    }

    public int firstPaletteSlot() {
        for (int i = 0; i < MAX_PALETTES; i++) {
            if (palettePresent[i] != 0) {
                return i;
            }
        }
        return -1;
    }

    public static ArtFile decode(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        ArtFile art = new ArtFile();

        art.flags = b.getInt();
        art.fps = b.getInt();
        art.bpp = b.getInt();
        if (art.bpp != 8) {
            throw new IllegalArgumentException("unsupported bpp=" + art.bpp);
        }
        for (int i = 0; i < MAX_PALETTES; i++) {
            art.palettePresent[i] = b.getInt();
        }
        art.actionFrame = b.getInt();
        art.numFrames = b.getInt();
        b.position(b.position() + 4 * MAX_ROTATIONS);   // skip frame-table ptrs
        b.position(b.position() + 4 * MAX_ROTATIONS);   // skip data_size[8]
        b.position(b.position() + 4 * MAX_ROTATIONS);   // skip pixel-table ptrs
        // (the three skipped tables total 3*32 = 96 bytes; header == 132)

        art.numRotations = (art.flags & 0x01) != 0 ? 1 : MAX_ROTATIONS;

        for (int slot = 0; slot < MAX_PALETTES; slot++) {
            if (art.palettePresent[slot] != 0) {
                int[] pal = new int[256];
                for (int i = 0; i < 256; i++) {
                    pal[i] = b.getInt() & 0x00FFFFFF;
                }
                art.palettes[slot] = pal;
            }
        }

        art.frames = new Frame[art.numRotations][art.numFrames];
        for (int rot = 0; rot < art.numRotations; rot++) {
            for (int f = 0; f < art.numFrames; f++) {
                Frame fr = new Frame();
                fr.width = b.getInt();
                fr.height = b.getInt();
                fr.dataSize = b.getInt();
                fr.hotX = b.getInt();
                fr.hotY = b.getInt();
                fr.offsetX = b.getInt();
                fr.offsetY = b.getInt();
                art.frames[rot][f] = fr;
            }
        }

        for (int rot = 0; rot < art.numRotations; rot++) {
            for (Frame fr : art.frames[rot]) {
                fr.indices = decodeFramePixels(b, fr);
            }
        }
        return art;
    }

    private static byte[] decodeFramePixels(ByteBuffer b, Frame fr) {
        int n = fr.width * fr.height;
        byte[] out = new byte[n];
        if (fr.dataSize == n) {                 // raw / uncompressed
            b.get(out);
            return out;
        }
        if (fr.dataSize <= 0) {                 // empty frame
            return out;
        }
        int pos = 0;
        int cnt = 0;
        while (cnt < fr.dataSize) {
            int value = b.get() & 0xFF;
            int len = value & 0x7F;
            if ((value & 0x80) != 0) {          // literal run
                b.get(out, pos, len);
                cnt += 1 + len;
            } else {                            // color run
                byte color = b.get();
                java.util.Arrays.fill(out, pos, pos + len, color);
                cnt += 2;
            }
            pos += len;
        }
        if (pos != n) {
            throw new IllegalStateException(
                    "RLE produced " + pos + " px, expected " + n);
        }
        return out;
    }

    /**
     * Convert a frame to RGBA8888 bytes using the given palette slot. Index 0
     * (the color key) becomes fully transparent. Matches the Python reference's
     * {@code frame_to_rgba}.
     */
    public byte[] frameToRgba(Frame fr, int slot) {
        int[] pal = palettes[slot];
        byte[] out = new byte[fr.width * fr.height * 4];
        for (int i = 0; i < fr.indices.length; i++) {
            int idx = fr.indices[i] & 0xFF;
            if (idx == 0) {
                continue;                       // transparent
            }
            int rgb = pal[idx];
            int o = i * 4;
            out[o] = (byte) ((rgb >> 16) & 0xFF);
            out[o + 1] = (byte) ((rgb >> 8) & 0xFF);
            out[o + 2] = (byte) (rgb & 0xFF);
            out[o + 3] = (byte) 0xFF;
        }
        return out;
    }
}
