package com.arcanum.ce.game;

import java.nio.ByteBuffer;

/**
 * Reads a single serialized object from a sector object list, ported from
 * {@code obj.c} ({@code obj_read} / {@code obj_inst_read_file} /
 * {@code obj_proto_read_file}) and {@code obj_private.c}
 * ({@code obj_data_read_file_fast}, {@code sa_read_no_dealloc},
 * {@code bitset_read_file}).
 *
 * <p>Field values are dif-gated by the object's {@code field_48} bitmap for
 * instances; prototypes serialize every field unconditionally.
 */
public final class ObjReader {

    /** OBJ_FILE_VERSION — every serialized object starts with this int32. */
    public static final int OBJ_FILE_VERSION = 119;

    /** ObjectID / HANDLE serialized size (obj_id.h: sizeof(ObjectID) == 0x18). */
    public static final int OBJECT_ID_SIZE = ObjectID.SIZE;

    private ObjReader() {
    }

    /**
     * Read one object at the buffer's current position, advancing past it.
     * Mirrors {@code obj_read}: version, prototype_oid; then instance or proto
     * body depending on the prototype_oid's type.
     */
    public static GameObject read(ByteBuffer b) {
        int version = b.getInt();
        if (version != OBJ_FILE_VERSION) {
            throw new IllegalStateException("bad OBJ_FILE_VERSION " + version
                    + " (expected " + OBJ_FILE_VERSION + ") at offset "
                    + (b.position() - 4));
        }

        // The prototype_oid decides the body: OID_TYPE_BLOCKED means "this object
        // IS a prototype" (it inherits from nothing); anything else names the
        // prototype it inherits absent fields from (obj_field_fetch, obj.c).
        ObjectID protoOid = ObjectID.read(b);
        if (protoOid.isBlocked()) {
            return readProto(b, protoOid);
        }
        return readInstance(b, protoOid);
    }

    // obj_inst_read_file: oid(24), objType(4), num_fields(4), field_48 bitmap,
    // then the dif-gated field values.
    private static GameObject readInstance(ByteBuffer b, ObjectID protoOid) {
        ObjectID oid = ObjectID.read(b);
        int objType = b.getInt();
        b.getShort();                                   // num_fields: int16_t in the
                                                        // Object struct (obj.c), so
                                                        // only 2 bytes on disk. Ignored.

        int words = ObjectFields.wordCount(objType);
        int[] field48 = new int[words];
        for (int i = 0; i < words; i++) {
            field48[i] = b.getInt();
        }

        GameObject obj = new GameObject(objType, oid, protoOid);
        for (int[] range : ObjectFields.rangesForType(objType)) {
            for (int fld = range[0] + 1; fld < range[1]; fld++) {
                int ci = ObjectFields.cai[fld];
                int mask = 1 << ObjectFields.bit[fld];
                if ((field48[ci] & mask) != 0) {
                    obj.put(fld, readValue(b, ObjectFields.TYPE[fld]));
                }
            }
        }
        return obj;
    }

    // obj_proto_read_file: oid(24), objType(4), field_4C bitmap, then EVERY field
    // read unconditionally (protos are not dif-gated). Note there is no
    // num_fields int16 here — that belongs to the instance path only.
    private static GameObject readProto(ByteBuffer b, ObjectID protoOid) {
        ObjectID oid = ObjectID.read(b);                // the proto's own id:
                                                        // OID_TYPE_A, d.a = proto number
        int objType = b.getInt();

        int words = ObjectFields.wordCount(objType);
        b.position(b.position() + words * 4);           // field_4C (unused here)

        GameObject obj = new GameObject(objType, oid, protoOid);
        for (int[] range : ObjectFields.rangesForType(objType)) {
            for (int fld = range[0] + 1; fld < range[1]; fld++) {
                obj.put(fld, readValue(b, ObjectFields.TYPE[fld]));
            }
        }
        return obj;
    }

    // obj_data_read_file_fast: read one value of the given ObjDataType.
    private static Object readValue(ByteBuffer b, int odType) {
        switch (odType) {
        case ObjectFields.OD_INT32:
            return b.getInt();
        case ObjectFields.OD_INT64: {
            if (b.get() == 0) {
                return null;
            }
            return b.getLong();
        }
        case ObjectFields.OD_STRING: {
            if (b.get() == 0) {
                return null;
            }
            int size = b.getInt();
            byte[] s = new byte[size + 1];          // size+1 bytes (incl. NUL)
            b.get(s);
            int nul = size;                          // trailing NUL position
            return new String(s, 0, nul, java.nio.charset.StandardCharsets.US_ASCII);
        }
        case ObjectFields.OD_HANDLE: {
            if (b.get() == 0) {
                return null;
            }
            b.position(b.position() + OBJECT_ID_SIZE);
            return "<handle>";
        }
        case ObjectFields.OD_INT32_ARRAY:
        case ObjectFields.OD_INT64_ARRAY:
        case ObjectFields.OD_UINT32_ARRAY:
        case ObjectFields.OD_UINT64_ARRAY:
        case ObjectFields.OD_SCRIPT_ARRAY:
        case ObjectFields.OD_QUEST_ARRAY:
        case ObjectFields.OD_HANDLE_ARRAY: {
            if (b.get() == 0) {
                return null;
            }
            readSizeableArray(b);
            return "<array>";
        }
        case ObjectFields.OD_PTR:
        case ObjectFields.OD_PTR_ARRAY:
            throw new IllegalStateException("PTR field must never be serialized");
        default:
            throw new IllegalStateException("unreadable ObjDataType " + odType);
        }
    }

    // sa_read_no_dealloc + bitset_read_file: header (size, count, bitset_id),
    // size*count bytes of element data, then a bitset (bcnt, bcnt*4 bytes).
    private static void readSizeableArray(ByteBuffer b) {
        int size = b.getInt();
        int count = b.getInt();
        b.getInt();                                     // bitset_id (ignored)
        b.position(b.position() + size * count);        // element data
        int bcnt = b.getInt();
        b.position(b.position() + bcnt * 4);            // bitset words
    }
}
