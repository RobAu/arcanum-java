package com.arcanum.ce.tig.art;

/**
 * Decoding/encoding of {@code tig_art_id_t} packed identifiers.
 *
 * Ported from the bitfield logic in {@code first_party/tig/src/art.c}. An art id
 * is an unsigned 32-bit value (held in a Java {@code int}) whose top nibble is
 * the {@link Type} and whose remaining bits encode num / frame / rotation /
 * palette differently per type. Only decoding (used to render a given id) plus a
 * couple of {@code create} helpers are ported here; the per-type {@code create}
 * functions for critters/items/etc. can be added as needed.
 */
public final class ArtId {

    // -- type ----------------------------------------------------------------
    public static final int TYPE_TILE = 0;
    public static final int TYPE_WALL = 1;
    public static final int TYPE_CRITTER = 2;
    public static final int TYPE_PORTAL = 3;
    public static final int TYPE_SCENERY = 4;
    public static final int TYPE_INTERFACE = 5;
    public static final int TYPE_ITEM = 6;
    public static final int TYPE_CONTAINER = 7;
    public static final int TYPE_MISC = 8;
    public static final int TYPE_LIGHT = 9;
    public static final int TYPE_ROOF = 10;
    public static final int TYPE_FACADE = 11;
    public static final int TYPE_MONSTER = 12;
    public static final int TYPE_UNIQUE_NPC = 13;
    public static final int TYPE_EYE_CANDY = 14;

    // -- shifts / sizes (from art.c) -----------------------------------------
    static final int ART_ID_TYPE_SHIFT = 28;
    static final int ART_ID_PALETTE_SHIFT = 4;
    static final int ART_ID_ROTATION_SHIFT = 11;
    static final int ART_ID_NUM_SHIFT = 19;
    static final int ART_ID_MAX_NUM = 512;
    static final int ART_ID_FRAME_SHIFT = 14;
    static final int ART_ID_MAX_FRAME = 32;

    static final int INTERFACE_ID_MAX_NUM = 4096;
    static final int INTERFACE_ID_NUM_SHIFT = 16;
    static final int INTERFACE_ID_MAX_FRAME = 256;
    static final int INTERFACE_ID_FRAME_SHIFT = 8;

    static final int LIGHT_ID_MAX_FRAME = 128;
    static final int LIGHT_ID_FRAME_SHIFT = 12;
    static final int LIGHT_ID_ROTATION_SHIFT = 9;

    static final int EYE_CANDY_ID_MAX_FRAME = 128;
    static final int EYE_CANDY_ID_FRAME_SHIFT = 12;
    static final int EYE_CANDY_ID_ROTATION_SHIFT = 9;

    static final int FACADE_ID_MAX_FRAME = 1024;
    static final int FACADE_ID_FRAME_SHIFT = 1;

    static final int UNIQUE_NPC_ID_MAX_NUM = 256;
    static final int UNIQUE_NPC_ID_NUM_SHIFT = 20;

    static final int MAX_PALETTES = 4;
    static final int MAX_ROTATIONS = 8;

    private ArtId() {
    }

    /** tig_art_type */
    public static int type(int artId) {
        return artId >>> ART_ID_TYPE_SHIFT;
    }

    /** tig_art_num_get (type-dependent). */
    public static int num(int artId) {
        switch (type(artId)) {
            case TYPE_TILE:
            case TYPE_WALL:
            case TYPE_CRITTER:
            case TYPE_MONSTER:
                return 0;
            case TYPE_UNIQUE_NPC:
                return (artId >>> UNIQUE_NPC_ID_NUM_SHIFT) & (UNIQUE_NPC_ID_MAX_NUM - 1);
            case TYPE_ITEM:
                return (artId >>> 17) & 0x7FF;
            case TYPE_INTERFACE:
                return (artId >>> INTERFACE_ID_NUM_SHIFT) & (INTERFACE_ID_MAX_NUM - 1);
            default:
                return (artId >>> ART_ID_NUM_SHIFT) & (ART_ID_MAX_NUM - 1);
        }
    }

    /** tig_art_id_frame_get (type-dependent). */
    public static int frame(int artId) {
        switch (type(artId)) {
            case TYPE_TILE:
            case TYPE_WALL:
            case TYPE_ITEM:
                return 0;
            case TYPE_INTERFACE:
            case TYPE_MISC:
                return (artId >>> INTERFACE_ID_FRAME_SHIFT) & (INTERFACE_ID_MAX_FRAME - 1);
            case TYPE_FACADE:
                return (artId >>> FACADE_ID_FRAME_SHIFT) & (FACADE_ID_MAX_FRAME - 1);
            case TYPE_LIGHT:
                return (artId >>> LIGHT_ID_FRAME_SHIFT) & (LIGHT_ID_MAX_FRAME - 1);
            case TYPE_EYE_CANDY:
                return (artId >>> EYE_CANDY_ID_FRAME_SHIFT) & (EYE_CANDY_ID_MAX_FRAME - 1);
            default:
                return (artId >>> ART_ID_FRAME_SHIFT) & (ART_ID_MAX_FRAME - 1);
        }
    }

    /** tig_art_id_rotation_get (type-dependent). */
    public static int rotation(int artId) {
        switch (type(artId)) {
            case TYPE_TILE:
            case TYPE_INTERFACE:
            case TYPE_MISC:
            case TYPE_ROOF:
            case TYPE_ITEM:
            case TYPE_FACADE:
                return 0;
            case TYPE_LIGHT:
                return (artId >>> LIGHT_ID_ROTATION_SHIFT) & (MAX_ROTATIONS - 1);
            case TYPE_EYE_CANDY:
                return (artId >>> EYE_CANDY_ID_ROTATION_SHIFT) & (MAX_ROTATIONS - 1);
            default:
                return (artId >>> ART_ID_ROTATION_SHIFT) & (MAX_ROTATIONS - 1);
        }
    }

    /** tig_art_id_palette_get (type-dependent). */
    public static int palette(int artId) {
        switch (type(artId)) {
            case TYPE_LIGHT:
            case TYPE_FACADE:
                return 0;
            default:
                return (artId >>> ART_ID_PALETTE_SHIFT) & (MAX_PALETTES - 1);
        }
    }

    // -- create (subset) -----------------------------------------------------
    /** tig_art_misc_id_create; returns the packed id (no validation throw). */
    public static int miscIdCreate(int num, int palette) {
        return (TYPE_MISC << ART_ID_TYPE_SHIFT)
                | ((num & (ART_ID_MAX_NUM - 1)) << ART_ID_NUM_SHIFT)
                | ((palette & (MAX_PALETTES - 1)) << ART_ID_PALETTE_SHIFT);
    }

    /** tig_art_interface_id_create. */
    public static int interfaceIdCreate(int num, int frame, int a3, int palette) {
        return (TYPE_INTERFACE << ART_ID_TYPE_SHIFT)
                | ((num & (INTERFACE_ID_MAX_NUM - 1)) << INTERFACE_ID_NUM_SHIFT)
                | ((frame & (INTERFACE_ID_MAX_FRAME - 1)) << INTERFACE_ID_FRAME_SHIFT)
                | ((a3 & 1) << 7)
                | ((palette & (MAX_PALETTES - 1)) << ART_ID_PALETTE_SHIFT);
    }
}
