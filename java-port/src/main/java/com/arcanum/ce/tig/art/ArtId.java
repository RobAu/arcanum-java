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
    static final int FACADE_ID_NUM_LOW_SHIFT = 17;
    static final int FACADE_ID_NUM_HIGH_SHIFT = 27;

    static final int UNIQUE_NPC_ID_MAX_NUM = 256;
    static final int UNIQUE_NPC_ID_NUM_SHIFT = 20;

    // Item field shifts (art.c). Note the item `num` is NOT ART_ID_NUM_SHIFT --
    // tig_art_num_get special-cases items to (id >> 17) & 0x7FF, see num().
    static final int ITEM_ID_TYPE_SHIFT = 0;
    static final int ITEM_ID_MAX_TYPE = 16;
    static final int ITEM_ID_SUBTYPE_SHIFT = 6;
    static final int ITEM_ID_MAX_SUBTYPE = 16;
    static final int ITEM_ID_DISPOSITION_SHIFT = 12;
    static final int ITEM_ID_MAX_DISPOSITION = 4;
    static final int ITEM_ID_ARMOR_COVERAGE_SHIFT = 14;
    static final int ITEM_ID_MAX_ARMOR_COVERAGE = 8;

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

    /** tig_art_item_id_type_get: TIG_ART_ITEM_TYPE_* (WEAPON=0 .. GENERIC=9). */
    public static int itemType(int artId) {
        if (type(artId) != TYPE_ITEM) {
            return 0;
        }
        return (artId >>> ITEM_ID_TYPE_SHIFT) & (ITEM_ID_MAX_TYPE - 1);
    }

    /** tig_art_item_id_subtype_get. */
    public static int itemSubtype(int artId) {
        if (type(artId) != TYPE_ITEM) {
            return 0;
        }
        return (artId >>> ITEM_ID_SUBTYPE_SHIFT) & (ITEM_ID_MAX_SUBTYPE - 1);
    }

    /** tig_art_item_id_disposition_get: TIG_ART_ITEM_DISPOSITION_* (GROUND=0..SCHEMATIC=3). */
    public static int itemDisposition(int artId) {
        if (type(artId) != TYPE_ITEM) {
            return 0;
        }
        return (artId >>> ITEM_ID_DISPOSITION_SHIFT) & (ITEM_ID_MAX_DISPOSITION - 1);
    }

    /** tig_art_item_id_armor_coverage_get: TIG_ART_ARMOR_COVERAGE_* (TORSO=0..MEDALLION=6). */
    public static int itemArmorCoverage(int artId) {
        if (type(artId) != TYPE_ITEM) {
            return 0;
        }
        return (artId >>> ITEM_ID_ARMOR_COVERAGE_SHIFT) & (ITEM_ID_MAX_ARMOR_COVERAGE - 1);
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

    // -- tile (TIG_ART_TYPE_TILE) field getters (art.c) ----------------------
    // A tile id blends two terrain names; see tile-rendering-spec.md.
    static final int TILE_ID_NUM1_SHIFT = 22;
    static final int TILE_ID_NUM2_SHIFT = 16;
    static final int TILE_ID_TYPE_SHIFT = 8;
    static final int TILE_ID_FLIPPABLE1_SHIFT = 7;
    static final int TILE_ID_FLIPPABLE2_SHIFT = 6;
    static final int TILE_ID_MAX_NUM = 64;

    // dword_5BE880 / dword_5BE8C0 (art.c:181,201): blend-index remap tables used
    // by sub_503700/sub_5037B0 when the tile's flags bit 0 (flip) is set.
    private static final int[] TILE_BLEND_REMAP_880 = {
        0, 1, 8, 3, 4, 5, 6, 7, 8, 3, 10, 11, 6, 7, 14, 15,
    };
    private static final int[] TILE_BLEND_REMAP_8C0 = {
        0, 1, 2, 9, 4, 5, 12, 13, 2, 9, 10, 11, 12, 13, 14, 15,
    };

    /** tig_art_tile_id_num1_get: first terrain-name index. */
    public static int tileNum1(int artId) {
        return (artId >>> TILE_ID_NUM1_SHIFT) & (TILE_ID_MAX_NUM - 1);
    }

    /** tig_art_tile_id_num2_get: second terrain-name index. */
    public static int tileNum2(int artId) {
        return (artId >>> TILE_ID_NUM2_SHIFT) & (TILE_ID_MAX_NUM - 1);
    }

    /** tig_art_tile_id_type_get: 0 = indoor, 1 = outdoor. */
    public static int tileType(int artId) {
        return (artId >>> TILE_ID_TYPE_SHIFT) & 1;
    }

    public static int tileFlippable1(int artId) {
        return (artId >>> TILE_ID_FLIPPABLE1_SHIFT) & 1;
    }

    public static int tileFlippable2(int artId) {
        return (artId >>> TILE_ID_FLIPPABLE2_SHIFT) & 1;
    }

    /** tig_art_tile_id_flippable_get (TILE case): both flippable bits set. */
    public static boolean tileFlippable(int artId) {
        return tileFlippable1(artId) != 0 && tileFlippable2(artId) != 0;
    }

    /** tig_art_facade_id_num_get: facade name index (8 low bits + a high bit). */
    public static int facadeNum(int artId) {
        int num = (artId >>> FACADE_ID_NUM_LOW_SHIFT) & 0xFF;
        if ((artId & (1 << FACADE_ID_NUM_HIGH_SHIFT)) != 0) {
            num += 256;
        }
        return num;
    }

    /** tig_art_facade_id_walkable_get: low bit (a cliff face is not walkable). */
    public static boolean facadeWalkable(int artId) {
        return (artId & 1) != 0;
    }

    /** tig_art_id_flags_get for TILE/WALL/PORTAL/ROOF: low nibble. */
    public static int tileFlags(int artId) {
        return artId & 0xF;
    }

    /** sub_503700: blend index a3 (0..15), remapped when the flip flag is set. */
    public static int tileBlend(int artId) {
        int v = (artId >>> 12) & 0xF;
        if ((tileFlags(artId) & 1) != 0) {
            v = TILE_BLEND_REMAP_8C0[v];
        }
        return v;
    }

    /** sub_5037B0: variation index a4 (0..15). */
    public static int tileVariation(int artId) {
        int v = (artId >>> 9) & 7;
        int v1 = tileBlend(artId);
        if (TILE_BLEND_REMAP_8C0[v1] == TILE_BLEND_REMAP_880[v1]
                && (tileFlags(artId) & 1) != 0) {
            v += 8;
        }
        return v;
    }
}
