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

    // Critter / monster / scenery / container / eye-candy field shifts.
    static final int CRITTER_ID_SHIELD_SHIFT = 19;
    static final int CRITTER_ID_ARMOR_SHIFT = 20;
    static final int CRITTER_ID_BODY_TYPE_SHIFT = 24;
    static final int CRITTER_ID_GENDER_SHIFT = 27;
    static final int ANIM_SHIFT = 6;            // tig_art_id_anim_get: (id>>6)&0x1F
    static final int MONSTER_ID_ARMOR_SHIFT = 20;
    static final int MONSTER_ID_SPECIE_SHIFT = 23;
    static final int SCENERY_ID_TYPE_SHIFT = 6;
    static final int SCENERY_ID_MAX_TYPE = 32;
    static final int CONTAINER_ID_TYPE_SHIFT = 6;
    static final int CONTAINER_ID_MAX_TYPE = 32;
    static final int EYE_CANDY_ID_TYPE_SHIFT = 6;

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

    /** tig_art_critter_id_create. */
    public static int critterIdCreate(int gender, int bodyType, int armor, int shield,
                                      int frame, int rotation, int anim, int weapon,
                                      int palette) {
        return (TYPE_CRITTER << ART_ID_TYPE_SHIFT)
                | ((gender & 1) << CRITTER_ID_GENDER_SHIFT)
                | ((bodyType & 7) << CRITTER_ID_BODY_TYPE_SHIFT)
                | ((armor & 0xF) << CRITTER_ID_ARMOR_SHIFT)
                | ((shield & 1) << CRITTER_ID_SHIELD_SHIFT)
                | ((frame & 0x1F) << ART_ID_FRAME_SHIFT)
                | ((rotation & (MAX_ROTATIONS - 1)) << ART_ID_ROTATION_SHIFT)
                | ((anim & 0x1F) << ANIM_SHIFT)
                | ((palette & (MAX_PALETTES - 1)) << ART_ID_PALETTE_SHIFT)
                | (weapon & 0xF);
    }

    // -- critter / monster / etc. field getters (tig_art_*_get) --------------
    public static int anim(int artId) {
        return (artId >>> ANIM_SHIFT) & 0x1F;
    }

    public static int critterArmor(int artId) {
        return (artId >>> CRITTER_ID_ARMOR_SHIFT) & 0xF;
    }

    public static int critterBodyType(int artId) {
        return (artId >>> CRITTER_ID_BODY_TYPE_SHIFT) & 7;
    }

    public static int critterGender(int artId) {
        return (artId >>> CRITTER_ID_GENDER_SHIFT) & 1;
    }

    public static int critterShield(int artId) {
        return (artId >>> CRITTER_ID_SHIELD_SHIFT) & 1;
    }

    public static int critterWeapon(int artId) {
        return artId & 0xF;
    }

    public static int monsterArmor(int artId) {
        return (artId >>> MONSTER_ID_ARMOR_SHIFT) & 7;
    }

    public static int monsterSpecie(int artId) {
        return (artId >>> MONSTER_ID_SPECIE_SHIFT) & 0x3F;
    }

    public static int sceneryType(int artId) {
        return (artId >>> SCENERY_ID_TYPE_SHIFT) & (SCENERY_ID_MAX_TYPE - 1);
    }

    public static int containerType(int artId) {
        return (artId >>> CONTAINER_ID_TYPE_SHIFT) & (CONTAINER_ID_MAX_TYPE - 1);
    }

    public static int eyeCandyType(int artId) {
        return (artId >>> EYE_CANDY_ID_TYPE_SHIFT) & 0x7;
    }
}
