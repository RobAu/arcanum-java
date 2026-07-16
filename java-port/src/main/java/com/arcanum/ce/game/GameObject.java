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

    /**
     * This object's own persistent id. For a prototype this is OID_TYPE_A with
     * {@code d.a} = the proto number, and is the key {@link ProtoStore} indexes
     * it under (mirroring {@code obj_pool_perm_oid_set}).
     */
    public final ObjectID oid;

    /**
     * The id of the prototype this object inherits absent fields from
     * ({@code obj_field_fetch}). OID_TYPE_BLOCKED iff this object <em>is</em> a
     * prototype, in which case it inherits from nothing.
     */
    public final ObjectID prototypeOid;

    // Stored field values by ordinal. INT32 -> Integer, INT64 -> Long,
    // STRING -> String, arrays/handles -> a descriptive placeholder (their raw
    // bytes are consumed but not decoded here). Absent fields are simply missing.
    private final Map<Integer, Object> fields = new HashMap<>();

    GameObject(int type, ObjectID oid, ObjectID prototypeOid) {
        this.type = type;
        this.oid = oid;
        this.prototypeOid = prototypeOid;
        this.isProto = prototypeOid.isBlocked();
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

    /**
     * A field's effective value, falling back to this object's prototype when
     * the instance does not override it. Ports {@code obj_field_fetch}
     * ({@code obj.c}):
     *
     * <pre>
     * if (object->prototype_oid.type == OID_TYPE_BLOCKED) {   // this IS a proto
     *     storage_idx = sub_40CB40(object, fld);              // read own data
     * } else if (fld > OBJ_F_TRANSIENT_BEGIN &amp;&amp; fld &lt; OBJ_F_TRANSIENT_END) {
     *     ...transient_properties...
     * } else if (sub_40D320(object, fld)) {                   // instance override?
     *     storage_idx = sub_40D230(object, fld);              // read instance
     * } else {
     *     proto = obj_lock(obj_get_prototype_handle(object)); // else read the proto
     *     storage_idx = sub_40CB40(proto, fld);
     * }
     * </pre>
     *
     * <p>The C's {@code sub_40D320} test — "is this field's bit set in the
     * instance's dif bitmap" — is exactly {@link #has}, since {@link ObjReader}
     * stores a field iff that bit was set. The proto side reads
     * {@code proto->data} directly, so the fallback is a single hop: a proto's
     * own fields are never themselves resolved further (and can't be — a proto's
     * prototype_oid is BLOCKED).
     *
     * <p>The transient branch never applies here: transient ordinals are all
     * above {@code OBJ_F_TOTAL_NORMAL} (313) and are not serialized, so they
     * never reach a parsed object.
     *
     * @param protos the loaded prototypes, or null to skip the fallback
     * @return the resolved value, or null if neither instance nor proto has it
     */
    public Object resolved(int ordinal, ProtoStore protos) {
        if (isProto || has(ordinal)) {
            return fields.get(ordinal);
        }
        if (protos == null) {
            return null;
        }
        GameObject proto = protos.proto(prototypeOid);
        return proto == null ? null : proto.field(ordinal);
    }

    /** {@link #resolved} for an INT32 field, or 0 if unresolvable. */
    public int resolvedInt(int ordinal, ProtoStore protos) {
        Object v = resolved(ordinal, protos);
        return v instanceof Integer ? (Integer) v : 0;
    }

    /** {@link #resolved} for an INT64 field, or 0 if unresolvable. */
    public long resolvedLong(int ordinal, ProtoStore protos) {
        Object v = resolved(ordinal, protos);
        return v instanceof Long ? (Long) v : 0L;
    }

    /**
     * OBJ_F_CURRENT_AID resolved through the prototype — the art most objects
     * actually draw with, since the majority never override it on the instance.
     */
    public int currentAid(ProtoStore protos) {
        return resolvedInt(ObjectFields.OBJ_F_CURRENT_AID, protos);
    }

    /** OBJ_F_LOCATION resolved through the prototype. */
    public long location(ProtoStore protos) {
        return resolvedLong(ObjectFields.OBJ_F_LOCATION, protos);
    }

    @Override
    public String toString() {
        long loc = location();
        return String.format("GameObject{type=%d, loc=(%d,%d), aid=0x%08X, oid=%s%s}",
                type, Location.getX(loc), Location.getY(loc), currentAid(), oid,
                isProto ? ", proto" : ", proto=" + prototypeOid);
    }
}
