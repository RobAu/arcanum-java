package com.arcanum.ce.game;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.arcanum.ce.tig.TigFile;

/**
 * The terrain-tile layer of an Arcanum {@code .sec} sector, ported from the read
 * path in {@code sector.c} ({@code sector_load_game}) and
 * {@code sector_tile_list.c}. See {@code tile-rendering-spec.md}.
 *
 * <p>On disk a sector begins with the light list ({@code int32 count} then
 * {@code count} × 48-byte records), followed by 4096 {@code uint32} tile
 * {@code art_id}s (a 64×64 grid). This reads just those two sections — enough to
 * render terrain; roofs/objects/scripts that follow are ignored.
 */
public final class SectorFile {

    public static final int TILE_COUNT = 4096;          // 64 x 64
    private static final int LIGHT_RECORD_SIZE = 48;    // LightSerializedData (0x30)

    /** tile art_ids, indexed by {@link Location#tileIndexInSector}. */
    public final int[] tileArtIds;

    private SectorFile(int[] tileArtIds) {
        this.tileArtIds = tileArtIds;
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
        int lightCount = b.getInt();
        long skip = (long) lightCount * LIGHT_RECORD_SIZE;
        if (lightCount < 0 || b.remaining() < skip + (long) TILE_COUNT * 4) {
            return null;
        }
        b.position(b.position() + (int) skip);

        int[] tiles = new int[TILE_COUNT];
        for (int i = 0; i < TILE_COUNT; i++) {
            tiles[i] = b.getInt();
        }
        return new SectorFile(tiles);
    }

    /** Tile art id at sector-local (x, y), both in 0..63. */
    public int tileAt(int x, int y) {
        return tileArtIds[(x & 0x3F) | ((y & 0x3F) << 6)];
    }
}
