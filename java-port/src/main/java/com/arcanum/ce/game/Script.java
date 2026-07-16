package com.arcanum.ce.game;

import java.nio.ByteBuffer;

/**
 * One script attachment, ported from {@code Script} in {@code script.h}:
 *
 * <pre>
 * typedef struct ScriptHeader {
 *     unsigned int flags;
 *     unsigned int counters;
 * } ScriptHeader;
 * static_assert(sizeof(ScriptHeader) == 0x8, "wrong size");
 *
 * typedef struct Script {
 *     ScriptHeader hdr;
 *     int num;
 * } Script;
 * static_assert(sizeof(Script) == 0xC, "wrong size");
 * </pre>
 *
 * <p>These are the elements of an object's {@code OBJ_F_SCRIPTS_IDX}
 * ({@code OD_TYPE_SCRIPT_ARRAY}), keyed by {@link Sap} attachment point. The
 * {@code num} is the script number that {@link ScriptName} turns into a
 * {@code scr\}/{@code dlg\} path.
 */
public final class Script {

    /** {@code sizeof(Script)} — the serialized element size of a SCRIPT_ARRAY. */
    public static final int SIZE = 12;

    /** {@code ScriptHeader.flags}. */
    public final int flags;

    /** {@code ScriptHeader.counters} (four packed bytes; see {@code script.c}). */
    public final int counters;

    /** The script number — 0 means "no script attached". */
    public final int num;

    public Script(int flags, int counters, int num) {
        this.flags = flags;
        this.counters = counters;
        this.num = num;
    }

    /** Decode one 12-byte {@code Script} from a little-endian buffer. */
    public static Script read(ByteBuffer b) {
        return new Script(b.getInt(), b.getInt(), b.getInt());
    }

    /**
     * True if no script is attached. {@code sa_get} zero-fills absent keys and
     * callers test {@code scr.num == 0} (e.g. {@code dialog.c},
     * {@code dialog_ui.c:149}), so a zero num is the C's own "none" marker.
     */
    public boolean isEmpty() {
        return num == 0;
    }

    @Override
    public String toString() {
        return String.format("Script{num=%d, flags=0x%08X, counters=0x%08X}",
                num, flags, counters);
    }
}
