package com.arcanum.ce.tig.database;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for Arcanum {@code .dat} archives (the TIG database format).
 *
 * Ported 1:1 from the validated Python reference {@code c2java/tools/dat_extract.py}
 * (recovered from {@code first_party/tig/src/database.c}). The archive index lives
 * in a trailer at the end of the file; entries are either stored raw or
 * zlib-compressed. Paths are normalized to lowercase with {@code '/'} separators.
 *
 * This is the asset-loading backend behind {@link com.arcanum.ce.tig.TigDatabase}
 * and, ultimately, {@link com.arcanum.ce.tig.TigFile}'s repository stack.
 */
public final class DatArchive implements AutoCloseable {

    public static final int FLAG_PLAIN = 0x01;
    public static final int FLAG_COMPRESSED = 0x02;
    public static final int FLAG_DIRECTORY = 0x400;
    public static final int FLAG_IGNORED = 0x800;

    private static final int FOURCC_DAT = fourcc(' ', 'T', 'A', 'D');
    private static final int FOURCC_DAT1 = fourcc('1', 'T', 'A', 'D');

    /** One archive entry (file or directory). */
    public static final class Entry {
        /**
         * The lookup key: separators as {@code /} and folded to lower case, since
         * the archives are Windows-cased and every lookup is case-insensitive.
         */
        public final String path;
        /**
         * The name as actually stored in the archive — separators normalized to
         * {@code /} but the case left alone (e.g. {@code scr/01324Virgil.scr}).
         *
         * <p>{@code tig_file_list_create} hands callers the real on-disk filename,
         * and some of them put it straight into a path they then show or build on
         * ({@code script_name_build_scr_name}), so the case has to survive listing.
         */
        public final String rawPath;
        public final int flags;
        public final int size;
        public final int compressedSize;
        public final long offset;   // absolute byte offset into the archive

        Entry(String path, String rawPath, int flags, int size, int compressedSize,
              long offset) {
            this.path = path;
            this.rawPath = rawPath;
            this.flags = flags;
            this.size = size;
            this.compressedSize = compressedSize;
            this.offset = offset;
        }

        public boolean isDirectory() {
            return (flags & FLAG_DIRECTORY) != 0;
        }

        public boolean isCompressed() {
            return (flags & FLAG_COMPRESSED) != 0;
        }
    }

    private final RandomAccessFile file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int fourcc;

    public DatArchive(java.io.File path) throws IOException {
        this.file = new RandomAccessFile(path, "r");
        parseIndex();
    }

    private void parseIndex() throws IOException {
        long size = file.length();
        if (size < 12) {
            throw new IOException("file too small to be a .dat");
        }
        // Trailer: read the last 12 bytes.
        byte[] tail = readAt(size - 12, 12);
        ByteBuffer tb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
        fourcc = tb.getInt();
        tb.getInt();                              // name_table_size (informational)
        int entryTableOffset = tb.getInt();
        if (fourcc != FOURCC_DAT && fourcc != FOURCC_DAT1) {
            throw new IOException("bad magic (not a TIG .dat)");
        }

        long etsPos = size - 4 - entryTableOffset;
        int entryTableSize = readInt(etsPos);
        long baseOffset = size - entryTableSize - entryTableOffset;

        long pos = etsPos + 4;
        int count = readInt(pos);
        pos += 4;
        // Index region runs to the start of the trailer.
        byte[] idx = readAt(pos, (int) ((size - 12) - pos));
        ByteBuffer b = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int nameSize = b.getInt();
            byte[] raw = new byte[nameSize];
            b.get(raw);
            b.getInt();                           // reserved
            int flags = b.getInt();
            int usize = b.getInt();
            int csize = b.getInt();
            int off = b.getInt();
            String rawName = normalize(raw, false);
            String name = rawName.toLowerCase();
            entries.put(name,
                    new Entry(name, rawName, flags, usize, csize, off + baseOffset));
        }
    }

    /** Separators to {@code /}, optionally folded to lower case. */
    private static String normalize(byte[] raw, boolean fold) {
        int len = raw.length;
        while (len > 0 && raw[len - 1] == 0) {    // strip NUL terminator
            len--;
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = (char) (raw[i] & 0xFF);      // latin1
            c = c == '\\' ? '/' : c;
            sb.append(fold ? Character.toLowerCase(c) : c);
        }
        return sb.toString();
    }

    /** All non-directory entries. */
    public List<Entry> files() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!e.isDirectory()) {
                out.add(e);
            }
        }
        return out;
    }

    public Entry get(String internalPath) {
        return entries.get(internalPath.replace('\\', '/').toLowerCase());
    }

    public boolean contains(String internalPath) {
        return get(internalPath) != null;
    }

    /** Read (and decompress if needed) an entry's full contents. */
    public byte[] read(String internalPath) throws IOException {
        Entry e = get(internalPath);
        if (e == null) {
            throw new IOException("no such entry: " + internalPath);
        }
        return read(e);
    }

    public byte[] read(Entry e) throws IOException {
        if (e.isDirectory()) {
            return new byte[0];
        }
        int onDisk = e.isCompressed() ? e.compressedSize : e.size;
        byte[] blob = readAt(e.offset, onDisk);
        if (!e.isCompressed()) {
            return blob;
        }
        byte[] out = new byte[e.size];
        Inflater inf = new Inflater();
        inf.setInput(blob);
        try {
            int n = inf.inflate(out);
            if (n != e.size) {
                throw new IOException("size mismatch for " + e.path
                        + ": got " + n + ", expected " + e.size);
            }
        } catch (DataFormatException ex) {
            throw new IOException("inflate failed for " + e.path, ex);
        } finally {
            inf.end();
        }
        return out;
    }

    private byte[] readAt(long offset, int len) throws IOException {
        byte[] buf = new byte[len];
        file.seek(offset);
        file.readFully(buf);
        return buf;
    }

    private int readInt(long offset) throws IOException {
        byte[] b = readAt(offset, 4);
        return (b[0] & 0xFF) | (b[1] & 0xFF) << 8
                | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
    }

    private static int fourcc(char a, char b, char c, char d) {
        return (a & 0xFF) | (b & 0xFF) << 8 | (c & 0xFF) << 16 | (d & 0xFF) << 24;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
