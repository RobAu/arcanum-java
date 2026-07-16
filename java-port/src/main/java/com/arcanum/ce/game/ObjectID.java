package com.arcanum.ce.game;

import java.nio.ByteBuffer;

/**
 * An object's persistent identity, ported from {@code ObjectID} ({@code obj_id.h}).
 * Serialized as exactly 24 bytes:
 *
 * <pre>
 * typedef struct ObjectID {
 *     int16_t type;          // 0x00
 *     int16_t padding_2;     // 0x02
 *     int     padding_4;     // 0x04
 *     union {                // 0x08 .. 0x17 (16 bytes)
 *         int64_t   h;
 *         int       a;
 *         TigGuid   g;       // uint8_t data[16]
 *         ObjectID_P p;      // { int64_t location; int temp_id; int map; }
 *     } d;
 * } ObjectID;
 * </pre>
 *
 * <p>Only {@link #type} plus the part of the union that type selects is
 * meaningful — the padding and any union bytes beyond the active member hold
 * uninitialized stack garbage on disk. Verified in {@code 010143 - Food.pro},
 * whose own oid at offset 0x1C reads type=1 (OID_TYPE_A), padding_4=0x033C85E2
 * (garbage), then union bytes {@code 9f 27 00 00 | 02 00 00 00 | da 85 3c 03 |
 * b8 4a c2 1c} — {@code d.a} = 0x279F = 10143 (matching the filename
 * {@code 010143}) with 12 bytes of garbage after it. Any equality or hashing
 * that looked at those bytes would fail to match.
 *
 * <p>{@link #equals}/{@link #hashCode} therefore compare only the active member,
 * mirroring {@code objid_is_equal} (obj_id.c), so this can key a prototype map.
 */
public final class ObjectID {

    /** sizeof(ObjectID) — {@code static_assert(sizeof(ObjectID) == 0x18)}. */
    public static final int SIZE = 24;

    // OID_TYPE_* (obj_id.h).
    public static final short TYPE_HANDLE = -2;
    public static final short TYPE_BLOCKED = -1;
    public static final short TYPE_NULL = 0;
    public static final short TYPE_A = 1;
    public static final short TYPE_GUID = 2;
    public static final short TYPE_P = 3;

    /** The OID_TYPE_* discriminator selecting which union member is live. */
    public final short type;

    // The 16-byte union, as two little-endian halves:
    //   d0 = bytes 0x00..0x07 of the union  (h, a in its low int, p.location, g[0..7])
    //   d1 = bytes 0x08..0x0F of the union  (p.temp_id | p.map, g[8..15])
    private final long d0;
    private final long d1;

    public ObjectID(short type, long d0, long d1) {
        this.type = type;
        this.d0 = d0;
        this.d1 = d1;
    }

    /** {@code sub_4E6540} (obj_id.c): an OID_TYPE_A id — how protos identify. */
    public static ObjectID ofA(int a) {
        return new ObjectID(TYPE_A, a & 0xFFFFFFFFL, 0);
    }

    /** Read one 24-byte ObjectID at the buffer's position, advancing past it. */
    public static ObjectID read(ByteBuffer b) {
        short type = b.getShort();
        b.getShort();                   // padding_2 (garbage on disk)
        b.getInt();                     // padding_4 (garbage on disk)
        long d0 = b.getLong();
        long d1 = b.getLong();
        return new ObjectID(type, d0, d1);
    }

    /** {@code d.a} — for OID_TYPE_A, the prototype number (e.g. 10143). */
    public int a() {
        return (int) d0;
    }

    /** {@code d.h} — for OID_TYPE_HANDLE. */
    public long h() {
        return d0;
    }

    /** {@code d.p.location} — for OID_TYPE_P. */
    public long pLocation() {
        return d0;
    }

    /** {@code d.p.temp_id} — for OID_TYPE_P. */
    public int pTempId() {
        return (int) d1;
    }

    /** {@code d.p.map} — for OID_TYPE_P. */
    public int pMap() {
        return (int) (d1 >>> 32);
    }

    /** {@code d.g} — the 16 raw TigGuid bytes, for OID_TYPE_GUID. */
    public byte[] guid() {
        byte[] g = new byte[16];
        for (int i = 0; i < 8; i++) {
            g[i] = (byte) (d0 >>> (8 * i));
            g[8 + i] = (byte) (d1 >>> (8 * i));
        }
        return g;
    }

    /** {@code objid_is_valid} — a usable, non-null id. */
    public boolean isValid() {
        return type == TYPE_A || type == TYPE_GUID || type == TYPE_P;
    }

    /** True iff this is the OID_TYPE_BLOCKED marker that flags a prototype. */
    public boolean isBlocked() {
        return type == TYPE_BLOCKED;
    }

    /**
     * Mirrors {@code objid_is_equal} (obj_id.c) exactly, including its quirk
     * that HANDLE and BLOCKED ids compare unequal even to themselves (the C's
     * switch has no case for them and falls through to {@code return false}).
     *
     * <p>{@link #equals} deliberately does <em>not</em> reproduce that quirk —
     * a non-reflexive equals would break {@link java.util.HashMap}. Use this
     * when C-identical semantics matter; the two agree on every id type the
     * prototype lookup actually uses (OID_TYPE_A).
     */
    public boolean isEqualC(ObjectID other) {
        if (other == null || type != other.type) {
            return false;
        }
        switch (type) {
        case TYPE_NULL:
            return true;
        case TYPE_A:
            return a() == other.a();
        case TYPE_GUID:
            return d0 == other.d0 && d1 == other.d1;
        case TYPE_P:
            return pLocation() == other.pLocation()
                    && pTempId() == other.pTempId()
                    && pMap() == other.pMap();
        default:
            return false;       // HANDLE / BLOCKED: never equal, per the C
        }
    }

    /**
     * Value equality over {@link #type} plus only the union member that type
     * selects — never the padding or the garbage bytes past the active member.
     *
     * <p>Same as {@link #isEqualC} except that BLOCKED/HANDLE ids compare by
     * their union rather than always false, keeping equals reflexive so this is
     * a legal hash key.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ObjectID)) {
            return false;
        }
        ObjectID other = (ObjectID) o;
        if (type != other.type) {
            return false;
        }
        switch (type) {
        case TYPE_NULL:
            return true;
        case TYPE_A:
            return a() == other.a();            // only the low int is meaningful
        case TYPE_HANDLE:
            return h() == other.h();            // only the low int64 is meaningful
        default:                                // GUID / P / BLOCKED: whole union
            return d0 == other.d0 && d1 == other.d1;
        }
    }

    @Override
    public int hashCode() {
        switch (type) {
        case TYPE_NULL:
            return TYPE_NULL;
        case TYPE_A:
            return 31 * TYPE_A + a();
        case TYPE_HANDLE:
            return 31 * TYPE_HANDLE + Long.hashCode(h());
        default:
            return 31 * (31 * type + Long.hashCode(d0)) + Long.hashCode(d1);
        }
    }

    /** {@code objid_id_to_str} (obj_id.c). */
    @Override
    public String toString() {
        switch (type) {
        case TYPE_HANDLE:
            return String.format("Handle_%X", h());
        case TYPE_BLOCKED:
            return "Blocked";
        case TYPE_NULL:
            return "Null";
        case TYPE_A:
            return "A_" + a();
        case TYPE_GUID: {
            StringBuilder sb = new StringBuilder("GUID_");
            for (byte x : guid()) {
                sb.append(String.format("%02X", x));
            }
            return sb.toString();
        }
        case TYPE_P:
            return String.format("P_%d_%d_%d", pLocation(), pTempId(), pMap());
        default:
            return "Bad(" + type + ")";
        }
    }
}
