package com.arcanum.ce.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * Pins the sparse-array semantics of {@link SizeableArray} — the port of
 * {@code sa.c}'s {@code sa_get}/{@code sa_has} over {@code obj_private.c}'s
 * {@code bitset_rank}/{@code bitset_test}.
 *
 * <p>The trap these guard: <b>a key is not a slot</b>. The element buffer is
 * packed in key order, so element {@code [key]} is the wrong element whenever any
 * lower key is absent. That is the normal case in real data, not an edge case —
 * see {@link ScriptArrayDataTest}, where every prototype carrying a SAP_DIALOG
 * has it at a slot other than 9.
 *
 * <p>Pure logic over synthetic buffers; no game data needed.
 */
class SizeableArrayTest {

    /**
     * Build a serialized SizeableArray the way the C writes one: header, the
     * packed element buffer, then the bitset ({@code bcnt}, {@code bcnt} words).
     *
     * @param keys     the present keys, ascending
     * @param elements one {@code size}-byte element per key, in the same order
     */
    private static ByteBuffer serialize(int size, int[] keys, byte[][] elements) {
        int maxKey = 0;
        for (int k : keys) {
            maxKey = Math.max(maxKey, k);
        }
        int bcnt = keys.length == 0 ? 0 : maxKey / 32 + 1;
        int[] words = new int[bcnt];
        for (int k : keys) {
            words[k / 32] |= 1 << (k % 32);
        }

        ByteBuffer b = ByteBuffer
                .allocate(12 + size * keys.length + 4 + 4 * bcnt)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(size);
        b.putInt(keys.length);       // count
        b.putInt(0);                 // bitset_id: a runtime slot, ignored on read
        for (byte[] e : elements) {
            b.put(e);
        }
        b.putInt(bcnt);
        for (int w : words) {
            b.putInt(w);
        }
        return b.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A 4-byte little-endian int element. */
    private static byte[] int32(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    @Test
    void keyIsNotSlotWhenLowerKeysAreAbsent() {
        // Keys 3, 9, 40 -> slots 0, 1, 2. This is THE trap: element[9] does not
        // exist at all (count == 3), and the element for key 9 lives at slot 1.
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {3, 9, 40},
                new byte[][] {int32(0xAAA), int32(0xBBB), int32(0xCCC)}));

        assertEquals(3, sa.count);
        assertEquals(0, sa.rank(3));
        assertEquals(1, sa.rank(9));
        assertEquals(2, sa.rank(40));

        assertEquals(0xAAA, sa.get(3).getInt());
        assertEquals(0xBBB, sa.get(9).getInt());   // slot 1, NOT slot 9
        assertEquals(0xCCC, sa.get(40).getInt());  // slot 2, spanning two bitset words
    }

    @Test
    void absentKeysReadAsNull() {
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {3, 9},
                new byte[][] {int32(1), int32(2)}));

        // sa_get memsets the caller's buffer for an absent key; we return null.
        for (int key : new int[] {0, 1, 2, 4, 8, 10, 31, 32, 1000}) {
            assertFalse(sa.has(key), "key " + key + " must be absent");
            assertNull(sa.get(key), "absent key " + key + " must not read an element");
        }
        assertTrue(sa.has(3));
        assertTrue(sa.has(9));
    }

    @Test
    void rankCountsSetBitsStrictlyBeforeTheKey() {
        // Dense low keys 0..4 -- here key == slot, so a naive reader would appear
        // to work. Pinning both cases keeps the two apart.
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {0, 1, 2, 3, 4},
                new byte[][] {int32(10), int32(11), int32(12), int32(13), int32(14)}));

        for (int k = 0; k < 5; k++) {
            assertEquals(k, sa.rank(k), "dense array: rank(" + k + ") should be " + k);
            assertEquals(10 + k, sa.get(k).getInt());
        }
        // rank is exclusive of the key itself: past the end it counts everything.
        assertEquals(5, sa.rank(5));
        assertEquals(5, sa.rank(100));
    }

    @Test
    void rankSpansStorageWordBoundaries() {
        // bitset_rank sums whole 32-bit words before the key's word, then the bits
        // below the key within it. Keys either side of 32 exercise both halves.
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {0, 31, 32, 33, 64, 95},
                new byte[][] {int32(0), int32(31), int32(32), int32(33), int32(64),
                              int32(95)}));

        assertEquals(0, sa.rank(0));
        assertEquals(1, sa.rank(31));   // word 0, bit 31
        assertEquals(2, sa.rank(32));   // word 1, bit 0 -> all of word 0 counted
        assertEquals(3, sa.rank(33));
        assertEquals(4, sa.rank(64));   // word 2, bit 0 -> words 0 and 1 counted
        assertEquals(5, sa.rank(95));   // word 2, bit 31

        for (int k : new int[] {0, 31, 32, 33, 64, 95}) {
            assertEquals(k, sa.get(k).getInt(), "wrong element for key " + k);
        }
    }

    @Test
    void bitsetTestIsFalsePastTheAllocatedWords() {
        // bitset_test: `if (idx > cnt - 1) return 0;` -- keys beyond the stored
        // words are absent rather than an out-of-bounds read.
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {1}, new byte[][] {int32(7)}));

        assertEquals(1, sa.bitsetWordCount());
        assertFalse(sa.has(32));
        assertFalse(sa.has(1000));
        assertNull(sa.get(1000));
        // Past the end, rank counts every allocated word (the C's else branch).
        assertEquals(1, sa.rank(1000));
    }

    @Test
    void emptyArrayHasNoKeys() {
        SizeableArray sa = SizeableArray.read(serialize(12, new int[0], new byte[0][]));
        assertEquals(0, sa.count);
        assertEquals(0, sa.keys().length);
        assertFalse(sa.has(0));
        assertNull(sa.get(0));
        assertEquals(0, sa.rank(0));
    }

    @Test
    void keysAreEnumeratedAscending() {
        SizeableArray sa = SizeableArray.read(serialize(4,
                new int[] {2, 9, 31, 32, 70},
                new byte[][] {int32(1), int32(2), int32(3), int32(4), int32(5)}));
        // bitset_enumerate walks words then bits, i.e. ascending key order -- the
        // same order the element buffer is packed in.
        assertArrayEqualsMsg(new int[] {2, 9, 31, 32, 70}, sa.keys());
    }

    @Test
    void readConsumesExactlyTheSerializedBytes() {
        // ObjReader relies on this: any drift here desynchronizes the whole object
        // stream, so pin that read() stops right after the last bitset word.
        ByteBuffer b = serialize(12, new int[] {9}, new byte[][] {new byte[12]});
        int total = b.remaining();
        SizeableArray.read(b);
        assertEquals(0, b.remaining(), "read must consume exactly " + total + " bytes");
    }

    @Test
    void decodesScriptElementsAtTheirRankedSlot() {
        // The real shape: 12-byte Script elements keyed by SAP. Keys 10 and 19
        // (FIRST_HEARTBEAT, HEARTBEAT) with SAP_DIALOG(9) absent -- reading
        // element[9] would be out of bounds; sa_get must report "no dialog".
        SizeableArray sa = SizeableArray.read(serialize(Script.SIZE,
                new int[] {Sap.FIRST_HEARTBEAT, Sap.HEARTBEAT},
                new byte[][] {script(0, 0, 1111), script(0, 0, 2222)}));

        assertFalse(sa.has(Sap.DIALOG));
        assertNull(sa.get(Sap.DIALOG));
        assertEquals(1111, Script.read(sa.get(Sap.FIRST_HEARTBEAT)).num);
        assertEquals(2222, Script.read(sa.get(Sap.HEARTBEAT)).num);
    }

    @Test
    void scriptDecodesFlagsCountersNum() {
        // Script == ScriptHeader{flags, counters} + num, 12 bytes, little-endian.
        SizeableArray sa = SizeableArray.read(serialize(Script.SIZE,
                new int[] {Sap.DIALOG},
                new byte[][] {script(0xDEADBEEF, 0x01020304, 1324)}));

        assertEquals(Script.SIZE, sa.size);
        Script s = Script.read(sa.get(Sap.DIALOG));
        assertNotNull(s);
        assertEquals(0xDEADBEEF, s.flags);
        assertEquals(0x01020304, s.counters);
        assertEquals(1324, s.num);
        assertFalse(s.isEmpty());
        assertTrue(new Script(0, 0, 0).isEmpty());
    }

    /** A serialized 12-byte Script. */
    private static byte[] script(int flags, int counters, int num) {
        return ByteBuffer.allocate(Script.SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(flags).putInt(counters).putInt(num).array();
    }

    private static void assertArrayEqualsMsg(int[] expected, int[] actual) {
        assertEquals(java.util.Arrays.toString(expected),
                java.util.Arrays.toString(actual));
    }
}
