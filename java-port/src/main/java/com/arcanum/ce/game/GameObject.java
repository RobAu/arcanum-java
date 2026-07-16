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

    /**
     * The script attached at a given {@link Sap} attachment point, resolved
     * through the prototype, or null if none is attached. Ports
     * {@code obj_arrayfield_script_get(obj, OBJ_F_SCRIPTS_IDX, sap, &scr)}
     * ({@code obj.c}) — {@code obj_arrayfield_fetch} → {@code obj_data_fetch} →
     * {@code sa_get} for {@code OD_TYPE_SCRIPT_ARRAY}.
     *
     * <p>{@code OBJ_F_SCRIPTS_IDX} is a {@link SizeableArray}, so {@code sap} is a
     * <em>key</em>, not an index — {@link SizeableArray#get} does the
     * {@code bitset_rank} hop. Reading element {@code [sap]} directly would return
     * a different script whenever a lower SAP is absent.
     *
     * <p>{@code sa_get} zero-fills an absent key and the C's callers then test
     * {@code scr.num == 0} ({@code dialog.c:3242}, {@code dialog_ui.c:149}); this
     * returns null for both cases (key absent, or present with num 0) so "no
     * script here" has one representation.
     *
     * @param sap a {@link Sap} attachment point, e.g. {@link Sap#DIALOG}
     */
    public Script script(int sap, ProtoStore protos) {
        Object v = resolved(ObjectFields.OBJ_F_SCRIPTS_IDX, protos);
        if (!(v instanceof SizeableArray)) {
            return null;
        }
        SizeableArray sa = (SizeableArray) v;
        if (sa.size != Script.SIZE) {
            // OD_TYPE_SCRIPT_ARRAY elements are sizeof(Script) == 0xC. A different
            // stride means we are not looking at the field we think we are.
            throw new IllegalStateException("OBJ_F_SCRIPTS_IDX element size is "
                    + sa.size + ", expected " + Script.SIZE);
        }
        java.nio.ByteBuffer e = sa.get(sap);
        if (e == null) {
            return null;
        }
        Script scr = Script.read(e);
        return scr.isEmpty() ? null : scr;
    }

    /** Every {@link Sap} key with a script attached, resolved through the prototype. */
    public int[] scriptKeys(ProtoStore protos) {
        Object v = resolved(ObjectFields.OBJ_F_SCRIPTS_IDX, protos);
        return v instanceof SizeableArray ? ((SizeableArray) v).keys() : new int[0];
    }

    @Override
    public String toString() {
        long loc = location();
        return String.format("GameObject{type=%d, loc=(%d,%d), aid=0x%08X, oid=%s%s}",
                type, Location.getX(loc), Location.getY(loc), currentAid(), oid,
                isProto ? ", proto" : ", proto=" + prototypeOid);
    }
}
