package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A serialized {@code SizeableArray} ({@code sa.c}) together with its bitset
 * ({@code obj_private.c}) — Arcanum's <em>sparse</em> array.
 *
 * <p><b>Keys are not slots.</b> The bitset records which keys are present; the
 * element buffer holds only the present elements, packed in ascending key order.
 * {@code sa_get} resolves a key to its slot via {@code bitset_rank}:
 *
 * <pre>
 * void sa_get(SizeableArray** sa_ptr, int key, void* value)
 * {
 *     if (!sa_has(sa_ptr, key)) {
 *         memset(value, 0, (*sa_ptr)-&gt;size);   // absent key reads as zeroes
 *         return;
 *     }
 *     index = bitset_rank((*sa_ptr)-&gt;bitset_id, key);   // # set bits BEFORE key
 *     memcpy(value, (uint8_t*)sa_data(*sa_ptr) + (*sa_ptr)-&gt;size * index,
 *         (*sa_ptr)-&gt;size);
 * }
 * </pre>
 *
 * <p>So indexing the element buffer by the raw key returns a <em>different
 * element</em> whenever any lower key is absent — silently, with no error. Always
 * go through {@link #get}.
 *
 * <p>Wire format, from {@code sa_read_no_dealloc} + {@code bitset_read_file}:
 * <pre>
 * int32 size          element size in bytes
 * int32 count         number of elements present
 * int32 bitset_id     a runtime id; meaningless on disk, discarded
 * byte[size * count]  element buffer, packed in ascending key order
 * int32 bcnt          number of 32-bit bitset storage words
 * int32[bcnt]         the bitset words
 * </pre>
 */
public final class SizeableArray {

    /** Bits per bitset storage item ({@code BITS_PER_STORAGE_ITEM}, obj_private.c). */
    private static final int BITS_PER_STORAGE_ITEM = 32;

    /** {@code sizeof(SizeableArray)} — the {size, count, bitset_id} header (sa.h). */
    public static final int HEADER_SIZE = 12;

    /** Element size in bytes ({@code SizeableArray.size}). */
    public final int size;

    /** Number of elements actually stored ({@code SizeableArray.count}). */
    public final int count;

    /** The packed element buffer: {@code size * count} bytes, in ascending key order. */
    private final byte[] data;

    /** The bitset's 32-bit storage words — which keys are present. */
    private final int[] bitset;

    private SizeableArray(int size, int count, byte[] data, int[] bitset) {
        this.size = size;
        this.count = count;
        this.data = data;
        this.bitset = bitset;
    }

    /**
     * Read one serialized array at the buffer's position, advancing past it.
     * Ports {@code sa_read_no_dealloc} followed by {@code bitset_read_file}.
     */
    public static SizeableArray read(ByteBuffer b) {
        int size = b.getInt();
        int count = b.getInt();
        b.getInt();                                     // bitset_id: a runtime slot
                                                        // index, re-allocated on load
                                                        // (bitset_alloc). Not data.
        byte[] data = new byte[Math.multiplyExact(size, count)];
        b.get(data);

        int bcnt = b.getInt();
        int[] bitset = new int[bcnt];
        for (int i = 0; i < bcnt; i++) {
            bitset[i] = b.getInt();
        }
        return new SizeableArray(size, count, data, bitset);
    }

    /**
     * {@code sa_has} / {@code bitset_test}: is {@code key} present?
     *
     * <pre>
     * bool bitset_test(int id, int pos)
     * {
     *     idx = bitset_index_of(pos);                  // pos / 32
     *     if (idx &gt; bitset_descriptors[id].cnt - 1) return 0;
     *     mask = bitset_mask_of(pos);                  // 1u &lt;&lt; (pos % 32)
     *     return (mask &amp; bitset_storage[idx + ...offset]) != 0;
     * }
     * </pre>
     */
    public boolean has(int key) {
        if (key < 0) {
            // Unreachable from real data (keys are SAP/stat ordinals). The C would
            // index bitset_storage negatively here; refuse instead.
            return false;
        }
        int idx = key / BITS_PER_STORAGE_ITEM;
        if (idx > bitset.length - 1) {
            return false;
        }
        return (bitset[idx] & (1 << (key % BITS_PER_STORAGE_ITEM))) != 0;
    }

    /**
     * {@code bitset_rank}: the number of set bits <em>strictly before</em>
     * {@code key} — i.e. the key's slot in the packed element buffer.
     *
     * <pre>
     * int bitset_rank(int id, int pos)
     * {
     *     cnt = 0;
     *     base_item_offset = bitset_descriptors[id].offset;
     *     idx = bitset_index_of(pos);
     *     stop_item_offset = base_item_offset + idx;
     *     if (idx &lt; bitset_descriptors[id].cnt) {
     *         cnt += bitset_count_bits(bitset_storage[stop_item_offset], pos % 32);
     *     } else {
     *         stop_item_offset = base_item_offset + bitset_descriptors[id].cnt;
     *     }
     *     while (base_item_offset &lt; stop_item_offset) {
     *         cnt += bitset_count_bits(bitset_storage[base_item_offset++], 32);
     *     }
     *     return cnt;
     * }
     * </pre>
     */
    public int rank(int key) {
        if (key < 0) {
            return 0;
        }
        int idx = key / BITS_PER_STORAGE_ITEM;
        int stop = idx;
        int cnt = 0;
        if (idx < bitset.length) {
            // Only the bits below `key` within its own storage word.
            cnt += countBits(bitset[idx], key % BITS_PER_STORAGE_ITEM);
        } else {
            // `key` is past the end: count every allocated word instead.
            stop = bitset.length;
        }
        for (int i = 0; i < stop; i++) {
            cnt += Integer.bitCount(bitset[i]);
        }
        return cnt;
    }

    /**
     * {@code bitset_count_bits(value, n)}: set bits among the low {@code n} bits.
     *
     * <p>The C does this with a 16-bit popcount table and a {@code popcount_masks[n]}
     * split-mask pair; {@code popcount_masks_init} builds entry {@code n} to select
     * exactly the {@code n} lowest bits (entry 32 selects all of them), so this is
     * a plain low-{@code n} mask + popcount.
     */
    private static int countBits(int value, int n) {
        if (n <= 0) {
            return 0;
        }
        // 1 << 32 is 1 in Java (shift count is masked), so the full word needs
        // its own case -- popcount_masks[32] selects all 32 bits.
        int mask = n >= BITS_PER_STORAGE_ITEM ? -1 : (1 << n) - 1;
        return Integer.bitCount(value & mask);
    }

    /**
     * {@code sa_get}: the element stored under {@code key}, as a little-endian
     * buffer of {@link #size} bytes, or null if the key is absent (the C zero-fills
     * the caller's buffer in that case).
     */
    public ByteBuffer get(int key) {
        if (!has(key)) {
            return null;
        }
        int index = rank(key);
        if (index < 0 || (index + 1) * size > data.length) {
            // count/bitset disagree -- corrupt data rather than a sparse miss.
            return null;
        }
        return ByteBuffer.wrap(data, index * size, size)
                .slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Every present key, ascending — {@code bitset_enumerate}'s iteration order
     * (which is exactly the element buffer's packing order).
     */
    public int[] keys() {
        int[] out = new int[count];
        int n = 0;
        for (int idx = 0; idx < bitset.length; idx++) {
            int word = bitset[idx];
            for (int bit = 0; bit < BITS_PER_STORAGE_ITEM; bit++) {
                if ((word & (1 << bit)) != 0) {
                    if (n < out.length) {
                        out[n] = idx * BITS_PER_STORAGE_ITEM + bit;
                    }
                    n++;
                }
            }
        }
        // count is the C's authority on how many elements the buffer holds; if the
        // bitset names a different number, trust the smaller so callers stay in bounds.
        if (n != out.length) {
            int[] exact = new int[Math.min(n, out.length)];
            System.arraycopy(out, 0, exact, 0, exact.length);
            return exact;
        }
        return out;
    }

    /** Number of 32-bit words in the bitset (its serialized {@code bcnt}). */
    public int bitsetWordCount() {
        return bitset.length;
    }

    @Override
    public String toString() {
        return "SizeableArray{size=" + size + ", count=" + count
                + ", keys=" + java.util.Arrays.toString(keys()) + "}";
    }
}
