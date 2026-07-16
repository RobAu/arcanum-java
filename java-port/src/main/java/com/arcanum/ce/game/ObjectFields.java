package com.arcanum.ce.game;

/**
 * Field metadata for Arcanum objects, ported from {@code obj.c} (the
 * {@code object_fields[]} table plus {@code sub_40A400}, {@code sub_40C030} and
 * {@code object_inst_enumerate_overridden_fields}) and the {@code ObjectType} /
 * {@code ObjDataType} enums in {@code obj.h} / {@code obj_data.h}.
 *
 * <p>There are 314 normal field ordinals ({@code OBJ_F_BEGIN=0} ..
 * {@code OBJ_F_TOTAL_NORMAL=313}). For each field we know its on-disk
 * {@link #TYPE wire type}, and (computed here at load time) its
 * {@link #cai change-array index} and {@link #bit bit} within the per-object
 * "which fields are overridden" bitmap ({@code field_48}). These three parallel
 * arrays are all {@link ObjReader} needs to walk a serialized object.
 */
public final class ObjectFields {

    // ObjDataType ordinals (obj_data.h). INVALID/BEGIN/END are structural, not
    // stored as values; enumeration ranges are [begin+1, end).
    public static final int OD_INVALID = 0;
    public static final int OD_BEGIN = 1;
    public static final int OD_END = 2;
    public static final int OD_INT32 = 3;
    public static final int OD_INT64 = 4;
    public static final int OD_INT32_ARRAY = 5;
    public static final int OD_INT64_ARRAY = 6;
    public static final int OD_UINT32_ARRAY = 7;
    public static final int OD_UINT64_ARRAY = 8;
    public static final int OD_SCRIPT_ARRAY = 9;
    public static final int OD_QUEST_ARRAY = 10;
    public static final int OD_STRING = 11;
    public static final int OD_HANDLE = 12;
    public static final int OD_HANDLE_ARRAY = 13;
    public static final int OD_PTR = 14;
    public static final int OD_PTR_ARRAY = 15;

    // ObjectType ordinals (obj.h).
    public static final int OBJ_TYPE_WALL = 0;
    public static final int OBJ_TYPE_PORTAL = 1;
    public static final int OBJ_TYPE_CONTAINER = 2;
    public static final int OBJ_TYPE_SCENERY = 3;
    public static final int OBJ_TYPE_PROJECTILE = 4;
    public static final int OBJ_TYPE_WEAPON = 5;
    public static final int OBJ_TYPE_AMMO = 6;
    public static final int OBJ_TYPE_ARMOR = 7;
    public static final int OBJ_TYPE_GOLD = 8;
    public static final int OBJ_TYPE_FOOD = 9;
    public static final int OBJ_TYPE_SCROLL = 10;
    public static final int OBJ_TYPE_KEY = 11;
    public static final int OBJ_TYPE_KEY_RING = 12;
    public static final int OBJ_TYPE_WRITTEN = 13;
    public static final int OBJ_TYPE_GENERIC = 14;
    public static final int OBJ_TYPE_PC = 15;
    public static final int OBJ_TYPE_NPC = 16;
    public static final int OBJ_TYPE_TRAP = 17;
    public static final int OBJ_TYPE_MONSTER = 18;
    public static final int OBJ_TYPE_UNIQUE_NPC = 19;

    /** OBJ_F_TOTAL_NORMAL — number of normal (serializable) field ordinals. */
    public static final int TOTAL_NORMAL = 313;

    // Named ordinals surfaced for rendering.
    public static final int OBJ_F_CURRENT_AID = 1;  // INT32
    public static final int OBJ_F_LOCATION = 2;     // INT64

    // Named ordinals surfaced for identification (object_examine, object.c:3934).
    // All verified against the obj.h enum's declaration order and the wire types
    // assigned in obj.c's object_fields[] init.
    public static final int OBJ_F_FLAGS = 19;       // INT32
    /**
     * INT32 — an <em>internal</em> object-name number, resolved through
     * {@code oemes\oname.mes} by {@code o_name_get}, NOT through
     * {@code description.mes}. See {@link OName} and {@code obj.c:1703}.
     */
    public static final int OBJ_F_NAME = 22;
    /** INT32 — the player-facing description number ({@code mes\description.mes}). */
    public static final int OBJ_F_DESCRIPTION = 23;
    /** SCRIPT_ARRAY — sparse, keyed by {@link Sap}. See {@link SizeableArray}. */
    public static final int OBJ_F_SCRIPTS_IDX = 32;
    public static final int OBJ_F_ITEM_DESCRIPTION_UNKNOWN = 98;      // INT32
    public static final int OBJ_F_KEY_KEY_ID = 186;                   // INT32
    public static final int OBJ_F_CRITTER_DESCRIPTION_UNKNOWN = 240;  // INT32
    public static final int OBJ_F_PC_PLAYER_NAME = 270;               // STRING

    // Section [begin, end) ordinal pairs (obj.h). BEGIN/END markers themselves
    // are TYPE==OD_BEGIN/OD_END; real fields are the ordinals strictly between.
    public static final int COMMON_BEGIN = 0, COMMON_END = 37;
    public static final int WALL_BEGIN = 38, WALL_END = 44;
    public static final int PORTAL_BEGIN = 45, PORTAL_END = 54;
    public static final int CONTAINER_BEGIN = 55, CONTAINER_END = 67;
    public static final int SCENERY_BEGIN = 68, SCENERY_END = 75;
    public static final int PROJECTILE_BEGIN = 76, PROJECTILE_END = 85;
    public static final int ITEM_BEGIN = 86, ITEM_END = 110;
    public static final int WEAPON_BEGIN = 111, WEAPON_END = 139;
    public static final int AMMO_BEGIN = 140, AMMO_END = 148;
    public static final int ARMOR_BEGIN = 149, ARMOR_END = 162;
    public static final int GOLD_BEGIN = 163, GOLD_END = 170;
    public static final int FOOD_BEGIN = 171, FOOD_END = 177;
    public static final int SCROLL_BEGIN = 178, SCROLL_END = 184;
    public static final int KEY_BEGIN = 185, KEY_END = 191;
    public static final int KEY_RING_BEGIN = 192, KEY_RING_END = 199;
    public static final int WRITTEN_BEGIN = 200, WRITTEN_END = 209;
    public static final int GENERIC_BEGIN = 210, GENERIC_END = 216;
    public static final int CRITTER_BEGIN = 217, CRITTER_END = 251;
    public static final int PC_BEGIN = 252, PC_END = 278;
    public static final int NPC_BEGIN = 279, NPC_END = 305;
    public static final int TRAP_BEGIN = 306, TRAP_END = 312;

    /**
     * ObjDataType per field ordinal (314 entries). Copied verbatim from the
     * {@code object_fields[].type} column in {@code obj.c}.
     */
    public static final int[] TYPE = {
        0, 3, 4, 3, 3, 3, 7, 7, 7, 3, 3, 3, 3, 3, 3, 3, 7, 7, 7, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 5, 9, 3, 3, 7, 8, 2, 1, 3,
        3, 3, 7, 8, 2, 1, 3, 3, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 3, 3,
        13, 3, 3, 3, 3, 7, 8, 2, 1, 3, 12, 3, 3, 7, 8, 2, 1, 3, 3, 3,
        12, 3, 3, 7, 8, 2, 1, 3, 12, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 3, 3, 5, 5, 5, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 7, 8, 2,
        1, 3, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 3, 3, 5, 5, 3, 3, 3, 3,
        7, 8, 2, 1, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 3, 7, 8, 2, 1, 3,
        3, 3, 7, 8, 2, 1, 3, 3, 3, 7, 8, 2, 1, 3, 7, 3, 3, 7, 8, 2,
        1, 3, 3, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 3, 7, 8, 2, 1, 3, 3,
        5, 5, 5, 7, 3, 3, 3, 3, 7, 7, 12, 3, 12, 12, 12, 12, 12, 3, 13, 3,
        3, 13, 4, 3, 3, 3, 3, 3, 3, 7, 8, 2, 1, 3, 3, 7, 8, 3, 3, 10,
        7, 8, 7, 8, 3, 8, 7, 7, 7, 3, 11, 3, 7, 7, 3, 3, 7, 8, 2, 1,
        3, 12, 3, 12, 12, 3, 3, 8, 3, 4, 4, 3, 3, 3, 12, 3, 3, 13, 7, 7,
        3, 3, 3, 7, 13, 2, 1, 3, 3, 3, 7, 8, 2, 0
    };

    /** change_array_idx per field ordinal (index into {@code field_48}). */
    public static final int[] cai = new int[TOTAL_NORMAL + 1];

    /** bit within {@code field_48[cai]} per field ordinal. */
    public static final int[] bit = new int[TOTAL_NORMAL + 1];

    static {
        // Mirror obj.c sub_40A400: walk fields in order, tracking the current
        // section's begin ordinal and word-index base. BEGIN starts a section
        // (and picks its base from an earlier already-computed field); END has
        // no field; every other ordinal is a real storable field.
        int beginOrd = 0;
        int base = 0;
        for (int fld = 0; fld <= TOTAL_NORMAL; fld++) {
            int t = TYPE[fld];
            if (t == OD_BEGIN) {
                beginOrd = fld;
                base = baseForSectionBegin(fld);
            } else if (t == OD_END) {
                // no field
            } else {
                int idx = fld - beginOrd - 1;
                cai[fld] = idx / 32 + base;
                bit[fld] = idx % 32;
            }
        }
    }

    private ObjectFields() {
    }

    // The word-index base for a section, keyed off the section's BEGIN ordinal.
    // Item/critter subtype sections continue the word span of their parent
    // section (ITEM continues COMMON's last CAI; PC/NPC continue CRITTER's).
    private static int baseForSectionBegin(int fld) {
        switch (fld) {
        case COMMON_BEGIN:
            return 0;
        // WALL, PORTAL, CONTAINER, SCENERY, PROJECTILE, ITEM, CRITTER, TRAP
        // all start their own word span right after COMMON's last field (36).
        case WALL_BEGIN:
        case PORTAL_BEGIN:
        case CONTAINER_BEGIN:
        case SCENERY_BEGIN:
        case PROJECTILE_BEGIN:
        case ITEM_BEGIN:
        case CRITTER_BEGIN:
        case TRAP_BEGIN:
            return cai[36] + 1;
        // The concrete item subtypes continue after ITEM's last field (109).
        case WEAPON_BEGIN:
        case AMMO_BEGIN:
        case ARMOR_BEGIN:
        case GOLD_BEGIN:
        case FOOD_BEGIN:
        case SCROLL_BEGIN:
        case KEY_BEGIN:
        case KEY_RING_BEGIN:
        case WRITTEN_BEGIN:
        case GENERIC_BEGIN:
            return cai[109] + 1;
        // PC/NPC continue after CRITTER's last field (250).
        case PC_BEGIN:
        case NPC_BEGIN:
            return cai[250] + 1;
        default:
            throw new IllegalStateException("unexpected section begin ordinal " + fld);
        }
    }

    /**
     * The ordered list of {@code [begin, end)} field ranges enumerated for an
     * object of the given type, mirroring
     * {@code object_inst_enumerate_overridden_fields} (obj.c). Every type starts
     * with COMMON; item subtypes add ITEM then their own; PC/NPC add CRITTER then
     * their own; unknown types (MONSTER/UNIQUE_NPC) get COMMON only.
     */
    public static int[][] rangesForType(int objType) {
        switch (objType) {
        case OBJ_TYPE_WALL:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {WALL_BEGIN, WALL_END}};
        case OBJ_TYPE_PORTAL:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {PORTAL_BEGIN, PORTAL_END}};
        case OBJ_TYPE_CONTAINER:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {CONTAINER_BEGIN, CONTAINER_END}};
        case OBJ_TYPE_SCENERY:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {SCENERY_BEGIN, SCENERY_END}};
        case OBJ_TYPE_PROJECTILE:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {PROJECTILE_BEGIN, PROJECTILE_END}};
        case OBJ_TYPE_WEAPON:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {WEAPON_BEGIN, WEAPON_END}};
        case OBJ_TYPE_AMMO:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {AMMO_BEGIN, AMMO_END}};
        case OBJ_TYPE_ARMOR:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {ARMOR_BEGIN, ARMOR_END}};
        case OBJ_TYPE_GOLD:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {GOLD_BEGIN, GOLD_END}};
        case OBJ_TYPE_FOOD:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {FOOD_BEGIN, FOOD_END}};
        case OBJ_TYPE_SCROLL:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {SCROLL_BEGIN, SCROLL_END}};
        case OBJ_TYPE_KEY:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {KEY_BEGIN, KEY_END}};
        case OBJ_TYPE_KEY_RING:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {KEY_RING_BEGIN, KEY_RING_END}};
        case OBJ_TYPE_WRITTEN:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {WRITTEN_BEGIN, WRITTEN_END}};
        case OBJ_TYPE_GENERIC:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {ITEM_BEGIN, ITEM_END}, {GENERIC_BEGIN, GENERIC_END}};
        case OBJ_TYPE_PC:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {CRITTER_BEGIN, CRITTER_END}, {PC_BEGIN, PC_END}};
        case OBJ_TYPE_NPC:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {CRITTER_BEGIN, CRITTER_END}, {NPC_BEGIN, NPC_END}};
        case OBJ_TYPE_TRAP:
            return new int[][] {{COMMON_BEGIN, COMMON_END}, {TRAP_BEGIN, TRAP_END}};
        default:
            // MONSTER/UNIQUE_NPC/unknown: COMMON only (obj.c default case).
            return new int[][] {{COMMON_BEGIN, COMMON_END}};
        }
    }

    /**
     * Number of int32 words in an object's {@code field_48} override bitmap,
     * mirroring {@code sub_40C030}: {@code 1 + change_array_idx} of the type's
     * last (highest-CAI) enumerated field.
     */
    public static int wordCount(int objType) {
        int max = -1;
        for (int[] range : rangesForType(objType)) {
            for (int fld = range[0] + 1; fld < range[1]; fld++) {
                if (cai[fld] > max) {
                    max = cai[fld];
                }
            }
        }
        return max + 1;
    }
}
