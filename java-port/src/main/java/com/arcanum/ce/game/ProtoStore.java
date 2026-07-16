package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.arcanum.ce.tig.TigDebug;
import com.arcanum.ce.tig.TigFile;

/**
 * Every object prototype in the game, indexed by its {@link ObjectID}. Ports the
 * loading half of {@code proto.c} ({@code proto_init}, ~line 261):
 *
 * <pre>
 * tig_file_list_create(&amp;file_list, "proto\\*.pro");
 * for (index = 0; index &lt; file_list.count; index++) {
 *     snprintf(path, sizeof(path), "proto\\%s", file_list.entries[index].path);
 *     stream = tig_file_fopen(path, "rb");
 *     if (stream != NULL) {
 *         obj_read(stream, &amp;obj);
 *         tig_file_fclose(stream);
 *     }
 * }
 * </pre>
 *
 * <p>Each {@code .pro} holds exactly one serialized object taking {@code obj_read}'s
 * prototype branch (its {@code prototype_oid} is OID_TYPE_BLOCKED). Reading one
 * registers it under its own oid via {@code obj_pool_perm_oid_set}, which is what
 * {@code obj_pool_perm_lookup(prototype_oid)} later resolves during
 * {@code obj_field_fetch} — the map here plays that role.
 *
 * <p>A prototype's own oid is OID_TYPE_A with {@code d.a} = its proto number:
 * {@code proto_save} writes {@code "proto\\%06d - %s.pro"} keyed on {@code oid.d.a},
 * which is why {@code 010143 - Food.pro} carries {@code d.a} == 10143.
 *
 * <p>Prototypes are immutable game data, so the store is loaded once and cached
 * ({@link #get}).
 */
public final class ProtoStore {

    /** The repository directory prototypes live in ({@code proto\*.pro}). */
    public static final String PROTO_DIR = "proto";
    public static final String PROTO_EXT = ".pro";

    private static ProtoStore cached;

    private final Map<ObjectID, GameObject> byOid;
    private final List<String> failures;
    private final List<String> overridden;
    private final int filesFound;

    private ProtoStore(Map<ObjectID, GameObject> byOid, List<String> failures,
                       List<String> overridden, int filesFound) {
        this.byOid = byOid;
        this.failures = failures;
        this.overridden = overridden;
        this.filesFound = filesFound;
    }

    /**
     * The prototype store, loading it on first call. Requires the repository
     * stack to already be registered (see {@code GameData.discoverAndRegister}).
     */
    public static synchronized ProtoStore get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** Drop the cache — for tests that re-register the repository stack. */
    public static synchronized void reset() {
        cached = null;
    }

    /**
     * Read every {@code proto\*.pro} in the repository stack.
     *
     * <p>Two files can legitimately carry the same oid: {@code proto_save} names
     * them {@code "%06d - %s.pro"} from the proto number <em>and</em> its name,
     * so renaming a prototype yields a second file for the same number — the
     * install has {@code 008221 - 2849 Officer's Uniform.pro} loose alongside
     * {@code 008221 - armor.pro} in Arcanum5.dat. The C reads both (they are
     * distinct filenames, so {@code tig_file_list_create} lists both) and
     * {@code obj_pool_perm_oid_set} keeps the last: "Entry already exists - just
     * update the handle". {@link TigFile#list} returns names in the C's
     * case-insensitive sorted order, so overwriting here resolves such
     * collisions identically.
     */
    public static ProtoStore load() {
        List<String> names = TigFile.list(PROTO_DIR, PROTO_EXT);
        Map<ObjectID, GameObject> byOid = new HashMap<>();
        List<String> failures = new ArrayList<>();
        List<String> overridden = new ArrayList<>();

        for (String name : names) {
            String path = PROTO_DIR + "\\" + name;
            byte[] bytes = TigFile.readBytes(path);
            if (bytes == null) {
                failures.add(name + ": unreadable");
                continue;
            }
            try {
                ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                GameObject obj = ObjReader.read(b);
                if (!obj.isProto) {
                    // obj_read took the instance branch: prototype_oid wasn't
                    // BLOCKED, so this file isn't a prototype at all.
                    failures.add(name + ": not a prototype (prototype_oid="
                            + obj.prototypeOid + ")");
                    continue;
                }
                if (b.remaining() != 0) {
                    // A .pro holds exactly one object; leftovers mean the field
                    // walk drifted out of sync with the on-disk layout.
                    failures.add(name + ": " + b.remaining() + " trailing byte(s)");
                    continue;
                }
                // Last read wins, per obj_pool_perm_oid_set.
                GameObject prev = byOid.put(obj.oid, obj);
                if (prev != null) {
                    overridden.add(name + " re-registers oid " + obj.oid);
                }
            } catch (RuntimeException e) {
                failures.add(name + ": " + e);
            }
        }

        if (!failures.isEmpty()) {
            TigDebug.println("proto_init: " + failures.size() + " of " + names.size()
                    + " proto file(s) failed to load");
        }
        TigDebug.println("proto_init: loaded " + byOid.size() + " prototype(s) from "
                + names.size() + " proto\\*.pro file(s)");
        return new ProtoStore(byOid, failures, overridden, names.size());
    }

    /**
     * The prototype with the given id, or null if it isn't loaded. Mirrors
     * {@code obj_pool_perm_lookup(object->prototype_oid)} as reached through
     * {@code obj_get_prototype_handle} (obj.c).
     */
    public GameObject proto(ObjectID protoOid) {
        return protoOid == null ? null : byOid.get(protoOid);
    }

    /** The prototype numbered {@code n} (i.e. OID_TYPE_A with {@code d.a == n}). */
    public GameObject byNumber(int n) {
        return byOid.get(ObjectID.ofA(n));
    }

    /** Number of prototypes successfully loaded and indexed. */
    public int size() {
        return byOid.size();
    }

    /** Number of {@code proto\*.pro} files discovered in the repository stack. */
    public int filesFound() {
        return filesFound;
    }

    /** One message per proto file that could not be loaded. */
    public List<String> failures() {
        return Collections.unmodifiableList(failures);
    }

    /**
     * One message per proto file whose oid was already registered by an
     * earlier file — normal, and resolved last-wins as the C does.
     */
    public List<String> overridden() {
        return Collections.unmodifiableList(overridden);
    }

    /** Every loaded prototype's id. */
    public java.util.Set<ObjectID> oids() {
        return Collections.unmodifiableSet(byOid.keySet());
    }

    /** Every loaded prototype. */
    public java.util.Collection<GameObject> protos() {
        return Collections.unmodifiableCollection(byOid.values());
    }
}
