package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.arcanum.ce.tig.TigFile;

/**
 * An Arcanum {@code .sec} sector, ported from the read path in {@code sector.c}
 * ({@code sector_load_editor} / {@code sector_load_game}),
 * {@code sector_tile_list.c}, and {@code sector_object_list.c}
 * ({@code objlist_load}). See {@code tile-rendering-spec.md}.
 *
 * <p>On disk a sector is a sequence of sections: the light list, the 4096
 * {@code uint32} tile {@code art_id}s (a 64×64 grid), an optional roof grid, a
 * format-version placeholder gating tile/sector scripts + townmap/block data,
 * and finally the object list. The object <em>count</em> lives as a trailing
 * {@code int32} at the very end of the file; the objects themselves are read
 * forward from just after the header sections. See {@link ObjReader} for the
 * per-object format.
 *
 * <p>This parses the tile layer (enough to render terrain) and the object list
 * (each object's {@link GameObject#location} / {@link GameObject#currentAid}).
 */
public final class SectorFile {

    public static final int TILE_COUNT = 4096;          // 64 x 64
    private static final int LIGHT_RECORD_SIZE = 48;    // LightSerializedData (0x30)
    private static final int ROOF_COUNT = 256;          // 16 x 16 roof grid
    private static final int TILE_SCRIPT_RECORD = 24;   // TileScriptListNodeSerializedData
    private static final int SCRIPT_SIZE = 12;          // Script (flags, counters, num)
    private static final int BLOCK_LIST_COUNT = 128;    // block list uint32s

    // Placeholder / format-version range (sector.c). Below PLACEHOLDER_BASE there
    // are no script/townmap sections, and the object list follows immediately.
    private static final int PLACEHOLDER_BASE = 0xAA0000;
    private static final int PLACEHOLDER_MAX = 0xAA0004;

    /** tile art_ids, indexed by {@link Location#tileIndexInSector}. */
    public final int[] tileArtIds;

    /** parsed objects from the sector's trailing object list (may be empty). */
    public final List<GameObject> objects;

    private SectorFile(int[] tileArtIds, List<GameObject> objects) {
        this.tileArtIds = tileArtIds;
        this.objects = objects;
    }

    /** Load + parse a {@code .sec} from the repository, or null if unavailable. */
    public static SectorFile load(String path) {
        byte[] bytes = TigFile.readBytes(path);
        return bytes != null ? parse(bytes) : null;
    }

    /** Parse a {@code .sec} byte image; null if it is too short / malformed. */
    public static SectorFile parse(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 4) {
            return null;
        }

        // 1. Lights: int32 count, then count * 48 bytes (skipped).
        int lightCount = b.getInt();
        long skip = (long) lightCount * LIGHT_RECORD_SIZE;
        if (lightCount < 0 || b.remaining() < skip + (long) TILE_COUNT * 4) {
            return null;
        }
        b.position(b.position() + (int) skip);

        // 2. Tiles: 4096 uint32 art_ids.
        int[] tiles = new int[TILE_COUNT];
        for (int i = 0; i < TILE_COUNT; i++) {
            tiles[i] = b.getInt();
        }

        // Everything past the tile layer is best-effort: if it does not parse
        // cleanly we still return a usable terrain-only sector.
        List<GameObject> objects;
        try {
            objects = parseObjects(b, bytes.length);
        } catch (RuntimeException e) {
            objects = Collections.emptyList();
        }
        return new SectorFile(tiles, objects);
    }

    // Continue past the tile layer through roofs / placeholder / scripts /
    // townmap / block list, then read the trailing object list.
    private static List<GameObject> parseObjects(ByteBuffer b, int fileLength) {
        // 3. Roofs: int32 empty; if 0, a 256*4 roof-art grid follows.
        int roofEmpty = b.getInt();
        if (roofEmpty == 0) {
            b.position(b.position() + ROOF_COUNT * 4);
        }

        // 4. Placeholder / format version. Out of range -> no script sections;
        // the object list follows immediately.
        int placeholder = b.getInt();
        boolean hasSections = placeholder >= PLACEHOLDER_BASE && placeholder <= PLACEHOLDER_MAX;

        if (hasSections && placeholder != PLACEHOLDER_BASE) {
            // 5a. Tile scripts: int32 count, then count * 24 bytes.
            int tileScriptCount = b.getInt();
            b.position(b.position() + tileScriptCount * TILE_SCRIPT_RECORD);

            // 5b. Sector script (Script = 12 bytes) at >= 0xAA0002.
            if (placeholder >= 0xAA0002) {
                b.position(b.position() + SCRIPT_SIZE);
            }
            // 5c. townmap_info + aptitude_adj + light_scheme + SectorSoundList
            // = 24 bytes at >= 0xAA0003.
            if (placeholder >= 0xAA0003) {
                b.position(b.position() + 24);
            }
            // 5d. Block list: 128 uint32 = 512 bytes at >= 0xAA0004.
            if (placeholder >= 0xAA0004) {
                b.position(b.position() + BLOCK_LIST_COUNT * 4);
            }
        }

        // 6. Object list (objlist_load): count is the trailing int32 at the very
        // end of the file; objects are read forward from here.
        if (fileLength < 4) {
            return Collections.emptyList();
        }
        int cnt = b.getInt(fileLength - 4);
        List<GameObject> objects = new ArrayList<>(Math.max(0, cnt));
        for (int i = 0; i < cnt; i++) {
            objects.add(ObjReader.read(b));
        }

        // Oracle: after the objects, the next int32 is the trailing count and the
        // position must be exactly fileLength-4.
        int trailing = b.getInt();
        if (b.position() != fileLength || trailing != cnt) {
            throw new IllegalStateException("object list misaligned: pos="
                    + (b.position() - 4) + " expected " + (fileLength - 4)
                    + ", trailing=" + trailing + " cnt=" + cnt);
        }
        return objects;
    }

    /** Tile art id at sector-local (x, y), both in 0..63. */
    public int tileAt(int x, int y) {
        return tileArtIds[(x & 0x3F) | ((y & 0x3F) << 6)];
    }
}
