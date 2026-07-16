package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.arcanum.ce.tig.TigDebug;
import com.arcanum.ce.tig.TigFile;

/**
 * A map's mobile objects — the NPCs, critters and loose items that live on the
 * map rather than in a sector's static object list. Ports the game (non-editor)
 * branch of {@code map_load_mobile} ({@code map.c}).
 *
 * <p>The whole map's mobiles are stored in a single file next to the sectors,
 * named after the map with {@link #obfuscateName} applied and no extension:
 * {@code maps\<obfuscated name>}. Its layout is a 16-byte {@link
 * com.arcanum.ce.tig.TigGuid} header followed by back-to-back serialized objects
 * ({@link ObjReader}) until end of file:
 *
 * <pre>
 * strcpy(path2, base_path);          // "maps\<MapName>"
 * map_obfuscate_name(&(path2[5]));   // obfuscate only the part after "maps\"
 * stream = tig_file_fopen(path2, "rb");
 * tig_file_fread(&guid, sizeof(guid), 1, stream);
 * while (obj_read(stream, &obj)) { }
 * if (tig_file_feof(stream) == 0) { ... error ... }
 * </pre>
 *
 * <p>The editor keeps the same data as one {@code .mob} file per object under
 * {@code maps\<MapName>\}; this is the packed game-side equivalent.
 *
 * <p>Note the file covers the <em>entire</em> map, every sector — callers that
 * render a single sector must filter by {@link Location#sectorIdFromLoc}.
 */
public final class MapMobiles {

    /** sizeof(TigGuid) — {@code uint8_t data[16]} (tig/guid.h). */
    public static final int GUID_SIZE = 16;

    private MapMobiles() {
    }

    /**
     * {@code map_obfuscate_name} (map.c): a ROT13 variant, then reverse.
     *
     * <p>It is <em>not</em> plain ROT13. The original wraps with
     * {@code *pch += 'A' - 'Z' + 1}, which is −24 rather than −26, so a letter
     * that wraps past 'Z'/'z' ends up shifted by −11 overall instead of −13.
     * That quirk is part of the on-disk naming and must be preserved exactly:
     * {@code Arcanum1-024-fixed} → {@code Ngpncjz1-024-svmrq} → reversed →
     * {@code qrmvs-420-1zjcnpgN}. Non-letters pass through untouched.
     */
    public static String obfuscateName(String str) {
        char[] pch = str.toCharArray();
        for (int i = 0; i < pch.length; i++) {
            char c = pch[i];
            if (c >= 'A' && c <= 'Z') {
                c += 13;
                if (c > 'Z') {
                    c += 'A' - 'Z' + 1;         // == -24, not -26 (sic)
                }
                pch[i] = c;
            } else if (c >= 'a' && c <= 'z') {
                c += 13;
                if (c > 'z') {
                    c += 'a' - 'z' + 1;         // == -24, not -26 (sic)
                }
                pch[i] = c;
            }
        }
        return new StringBuilder(new String(pch)).reverse().toString();   // SDL_strrev
    }

    /** Repository path of a map's mobile file: {@code maps\<obfuscated name>}. */
    public static String path(String mapName) {
        return "maps\\" + obfuscateName(mapName);
    }

    /**
     * Load every mobile object on {@code mapName}, or an empty list if the map
     * has no mobile file.
     *
     * <p>Mirrors {@code while (obj_read(stream, &obj)) {}} plus the {@code feof}
     * check that follows it: a clean parse consumes the file exactly. If the
     * stream stops early with bytes still remaining, the objects read so far are
     * returned and the shortfall is logged (the C treats this as a hard error).
     */
    public static List<GameObject> load(String mapName) {
        byte[] bytes = TigFile.readBytes(path(mapName));
        if (bytes == null) {
            TigDebug.println("map_load_mobile: no mobile file " + path(mapName));
            return Collections.emptyList();
        }
        Result r = parse(bytes);
        if (!r.consumedExactly()) {
            TigDebug.println("map_load_mobile: Error reading object from file "
                    + path(mapName) + " (" + r.bytesRemaining + " bytes left after "
                    + r.objects.size() + " objects"
                    + (r.error != null ? ": " + r.error : "") + ")");
        }
        return r.objects;
    }

    /** Outcome of parsing a mobile file — the objects plus the feof-equivalent. */
    public static final class Result {
        /** Objects read, in file order. */
        public final List<GameObject> objects;
        /** Bytes left unconsumed; 0 iff the stream ended exactly at EOF. */
        public final int bytesRemaining;
        /** Why the read stopped early, or null if it reached EOF cleanly. */
        public final String error;

        Result(List<GameObject> objects, int bytesRemaining, String error) {
            this.objects = objects;
            this.bytesRemaining = bytesRemaining;
            this.error = error;
        }

        /** The {@code tig_file_feof(stream) != 0} check in map_load_mobile. */
        public boolean consumedExactly() {
            return bytesRemaining == 0 && error == null;
        }
    }

    /**
     * Parse a mobile-file byte image: skip the GUID header, then read objects
     * until the buffer is exhausted. Never throws — a malformed object ends the
     * read and is reported through {@link Result}.
     */
    public static Result parse(byte[] bytes) {
        if (bytes.length < GUID_SIZE) {
            return new Result(Collections.emptyList(), bytes.length,
                    "truncated GUID header");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.position(GUID_SIZE);                  // fread(&guid, sizeof(guid), 1, stream)

        List<GameObject> objects = new ArrayList<>();
        while (b.remaining() > 0) {
            int start = b.position();
            try {
                objects.add(ObjReader.read(b));
            } catch (RuntimeException e) {
                // obj_read returned false mid-file: the C's feof check fails here.
                return new Result(objects, bytes.length - start, String.valueOf(e));
            }
        }
        return new Result(objects, 0, null);
    }
}
