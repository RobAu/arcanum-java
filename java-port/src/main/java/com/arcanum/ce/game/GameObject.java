package com.arcanum.ce.game;

import java.util.HashMap;
import java.util.Map;

/**
 * A single parsed object from a sector's trailing object list, ported from the
 * in-memory {@code Object} of {@code obj.c}. Only the fields actually present on
 * disk (dif-gated by the object's {@code field_48} bitmap) are retained.
 *
 * <p>{@link #location} and {@link #currentAid} are surfaced directly for
 * rendering; any other field can be fetched by ordinal via {@link #field(int)}.
 */
public final class GameObject {

    /** ObjectType ordinal (WALL=0 .. NPC=16, etc). */
    public final int type;

    /** Whether this was read via the prototype (blocked-oid) path. */
    public final boolean isProto;

    // Stored field values by ordinal. INT32 -> Integer, INT64 -> Long,
    // STRING -> String, arrays/handles -> a descriptive placeholder (their raw
    // bytes are consumed but not decoded here). Absent fields are simply missing.
    private final Map<Integer, Object> fields = new HashMap<>();

    GameObject(int type, boolean isProto) {
        this.type = type;
        this.isProto = isProto;
    }

    void put(int ordinal, Object value) {
        fields.put(ordinal, value);
    }

    /** Raw stored value for a field ordinal, or null if the field is absent. */
    public Object field(int ordinal) {
        return fields.get(ordinal);
    }

    /** True if the given field ordinal was present (overridden) on disk. */
    public boolean has(int ordinal) {
        return fields.containsKey(ordinal);
    }

    /**
     * OBJ_F_LOCATION (ordinal 2, INT64) — the packed world tile location, or 0
     * if absent. Use {@link Location#getX}/{@link Location#getY} to unpack.
     */
    public long location() {
        Object v = fields.get(ObjectFields.OBJ_F_LOCATION);
        return v instanceof Long ? (Long) v : 0L;
    }

    /** OBJ_F_CURRENT_AID (ordinal 1, INT32) — the current art id, or 0 if absent. */
    public int currentAid() {
        Object v = fields.get(ObjectFields.OBJ_F_CURRENT_AID);
        return v instanceof Integer ? (Integer) v : 0;
    }

    @Override
    public String toString() {
        long loc = location();
        return String.format("GameObject{type=%d, loc=(%d,%d), aid=0x%08X%s}",
                type, Location.getX(loc), Location.getY(loc), currentAid(),
                isProto ? ", proto" : "");
    }
}
